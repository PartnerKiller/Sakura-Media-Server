function getStoredToken() {
  try {
    return localStorage.getItem('token') || sessionStorage.getItem('token') || null;
  } catch (e) {
    return null;
  }
}

function getStoredUser() {
  try {
    const raw = localStorage.getItem('user') || sessionStorage.getItem('user');
    if (!raw || raw === 'undefined') return null;
    return JSON.parse(raw);
  } catch (e) {
    return null;
  }
}

// GLOBAL STATE
let state = {
  token: getStoredToken(),
  user: getStoredUser(),
  currentRoot: null,       // object { name, path }
  currentPath: '',         // absolute path on server
  roots: [],               // list of accessible root objects
  files: [],               // files in current folder
  users: [],               // admin users list
  activePermissionsUserId: null,
  activePermissionsList: [], // temp permissions during modal edit
  serverMetricsTimer: null,
  viewMode: localStorage.getItem('viewMode') || 'grid',
  sortBy: localStorage.getItem('sortBy') || 'name-asc',
  filterType: 'all',
  activeUploadXhr: null,
  isUploadCancelled: false,
  autoOpenFile: null,
  pickerCurrentPath: '',
  pickerSelectedPaths: [],
  selectedPaths: new Set(),
  clipboard: { action: null, paths: [] },
  copyMoveAction: 'copy',
  copyMoveSources: [],
  copyMoveTarget: ''
};

function safeBase64Encode(str) {
  if (!str) return '';
  const utf8Bytes = new TextEncoder().encode(str);
  let binary = '';
  for (let i = 0; i < utf8Bytes.length; i++) {
    binary += String.fromCharCode(utf8Bytes[i]);
  }
  return btoa(binary)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=/g, '');
}

function encodePathQuery(pathStr) {
  if (!pathStr) return '';
  return pathStr.split('/').map(segment => encodeURIComponent(segment)).join('/');
}

function formatBandwidth(mbps) {
  if (!mbps || mbps <= 0) return 'Unlimited';
  return mbps.toFixed(1) + ' MB/s';
}

function cancelUpload(e) {
  if (e) {
    try {
      e.preventDefault();
      e.stopPropagation();
    } catch (err) {}
  }
  state.isUploadCancelled = true;
  if (state.activeUploadXhr) {
    try {
      state.activeUploadXhr.abort();
    } catch (e) {}
    state.activeUploadXhr = null;
  }
  const progressContainer = document.getElementById('upload-progress-container');
  if (progressContainer) {
    progressContainer.style.display = 'none';
  }
  const fileInput = document.getElementById('file-input');
  if (fileInput) fileInput.value = '';
  const folderInput = document.getElementById('folder-input');
  if (folderInput) folderInput.value = '';
  browsePath(state.currentPath);
}
window.cancelUpload = cancelUpload;

document.addEventListener('click', (e) => {
  if (e.target && e.target.closest && e.target.closest('#btn-cancel-upload')) {
    cancelUpload(e);
  }
});

// API Fetch Helper
async function apiCall(endpoint, options = {}) {
  const headers = options.headers || {};
  if (state.token) {
    headers['Authorization'] = `Bearer ${state.token}`;
  }
  
  if (options.body && !(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
  }

  const response = await fetch(endpoint, {
    ...options,
    headers
  });

  if ((response.status === 401 || response.status === 403) && !endpoint.startsWith('/api/auth/login')) {
    // Session expired or unauthorized
    logout();
    throw new Error('Unauthorized or Session expired');
  }

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));
    throw new Error(errorData.error || `HTTP error! status: ${response.status}`);
  }

  return response.json().catch(() => ({}));
}

// INITIALIZATION
document.addEventListener('DOMContentLoaded', () => {
  initApp();
});

function safeAddListener(id, event, callback) {
  const el = document.getElementById(id);
  if (el) {
    el.addEventListener(event, callback);
  }
}

function closeAllMediaViewersSilently() {
  const player = document.getElementById('html5-video-player');
  if (player) {
    player.pause();
    player.removeAttribute('src');
    player.load();
    player.onerror = null;
    player.onplaying = null;
    player.ontimeupdate = null;
  }
  if (state.videoWatchdog) {
    clearTimeout(state.videoWatchdog);
    state.videoWatchdog = null;
  }
  const errorBanner = document.getElementById('video-error-banner');
  if (errorBanner) errorBanner.style.display = 'none';
  closeModal('modal-video-player');

  const img = document.getElementById('viewer-img');
  if (img) {
    img.removeAttribute('src');
  }
  closeModal('modal-image-viewer');
}

function initApp() {
  applyTheme();
  applyUiStyle();
  lucide.createIcons();
  
  if (state.token && state.user) {
    showDashboard();
  } else {
    showLogin();
  }

  // Handle browser back/forward buttons and hash navigation
  window.addEventListener('hashchange', () => {
    if (!state.token || !state.user || !state.roots.length) return;
    const hashPath = window.location.hash.substring(1) || '';
    const decoded = hashPath ? decodeURIComponent(hashPath) : null;
    
    if (decoded) {
      if (isFilePath(decoded)) {
        const lastSlashIndex = decoded.lastIndexOf('/');
        const parentPath = lastSlashIndex !== -1 ? decoded.substring(0, lastSlashIndex) : '';
        const fileName = lastSlashIndex !== -1 ? decoded.substring(lastSlashIndex + 1) : decoded;
        
        if (parentPath === state.currentPath) {
          const fileToOpen = state.files.find(f => f.name === fileName);
          if (fileToOpen) {
            const ext = fileToOpen.name.split('.').pop().toLowerCase();
            let category = 'file';
            if (['mp4', 'mkv', 'webm', 'avi', 'mov'].includes(ext)) category = 'video';
            else if (['png', 'jpg', 'jpeg', 'gif', 'webp'].includes(ext)) category = 'image';
            else if (['mp3', 'wav', 'ogg', 'flac', 'm4a'].includes(ext)) category = 'audio';
            openMedia(decoded, fileToOpen.name, category);
          }
        } else {
          state.autoOpenFile = fileName;
          const matchedRoot = findRootForPath(parentPath);
          if (matchedRoot) {
            state.currentRoot = matchedRoot;
            state.currentPath = parentPath;
            renderRoots();
            browsePath(parentPath);
          }
        }
      } else {
        if (decoded !== state.currentPath) {
          const matchedRoot = findRootForPath(decoded);
          if (matchedRoot) {
            state.currentRoot = matchedRoot;
            state.currentPath = decoded;
            renderRoots();
            browsePath(decoded);
          }
        }
        closeAllMediaViewersSilently();
      }
    } else {
      if (state.roots.length > 0 && state.currentPath !== state.roots[0].path) {
        selectRoot(state.roots[0]);
      }
      closeAllMediaViewersSilently();
    }
  });

  // Bind Login Form
  safeAddListener('login-form', 'submit', handleLogin);
  
  // Login Password Toggle
  const toggleBtn = document.getElementById('btn-toggle-login-password');
  if (toggleBtn) {
    toggleBtn.addEventListener('click', () => {
      const pwdInput = document.getElementById('password');
      if (pwdInput && pwdInput.type === 'password') {
        pwdInput.type = 'text';
        toggleBtn.innerHTML = '<i data-lucide="eye-off"></i>';
      } else if (pwdInput) {
        pwdInput.type = 'password';
        toggleBtn.innerHTML = '<i data-lucide="eye"></i>';
      }
      lucide.createIcons();
    });
  }
  
  // Bind Logout Button
  safeAddListener('btn-logout', 'click', logout);

  // Bind Logo Click (to return to Home Explorer)
  const sidebarHeader = document.querySelector('.sidebar-header');
  if (sidebarHeader) {
    sidebarHeader.style.cursor = 'pointer';
    sidebarHeader.addEventListener('click', () => {
      closeAllMediaViewersSilently();
      switchPanel('explorer');
      if (state.roots.length > 0) {
        selectRoot(state.roots[0]);
      }
    });
  }

  // Bind Sidebar Navigation
  safeAddListener('nav-explorer', 'click', () => switchPanel('explorer'));
  safeAddListener('nav-users', 'click', () => switchPanel('users'));
  safeAddListener('nav-server', 'click', () => switchPanel('server'));
  safeAddListener('nav-recycle-bin', 'click', () => switchPanel('recycle-bin'));
  safeAddListener('nav-profile', 'click', () => switchPanel('profile'));
  safeAddListener('btn-empty-recycle', 'click', handleEmptyRecycleBin);

  // Bind Mobile Bottom Navigation
  safeAddListener('mobile-nav-explorer', 'click', () => switchPanel('explorer'));
  safeAddListener('mobile-nav-users', 'click', () => switchPanel('users'));
  safeAddListener('mobile-nav-server', 'click', () => switchPanel('server'));
  safeAddListener('mobile-nav-recycle-bin', 'click', () => switchPanel('recycle-bin'));
  safeAddListener('mobile-nav-profile', 'click', () => switchPanel('profile'));
  safeAddListener('mobile-nav-logout', 'click', logout);

  // Bind Profile Settings
  safeAddListener('profile-settings-form', 'submit', handleUpdateProfile);
  safeAddListener('profile-avatar-input', 'change', handleAvatarUpload);
  safeAddListener('btn-remove-avatar', 'click', handleAvatarDelete);
  safeAddListener('btn-toggle-profile-password', 'click', () => {
    const passwordInput = document.getElementById('profile-password');
    const eyeIconBtn = document.getElementById('btn-toggle-profile-password');
    const eyeIcon = eyeIconBtn.querySelector('i');
    if (passwordInput.type === 'password') {
      passwordInput.type = 'text';
      eyeIcon.setAttribute('data-lucide', 'eye-off');
    } else {
      passwordInput.type = 'password';
      eyeIcon.setAttribute('data-lucide', 'eye');
    }
    lucide.createIcons();
  });

  // Bind Explorer Controls
  safeAddListener('btn-new-folder', 'click', () => openModal('modal-new-folder'));
  safeAddListener('new-folder-form', 'submit', handleCreateFolder);
  
  const fileInput = document.getElementById('file-input');
  if (fileInput) {
    safeAddListener('btn-upload-trigger', 'click', () => fileInput.click());
    fileInput.addEventListener('change', handleFileUpload);
  }

  const folderInput = document.getElementById('folder-input');
  if (folderInput) {
    safeAddListener('btn-upload-folder-trigger', 'click', () => folderInput.click());
    folderInput.addEventListener('change', handleFolderUpload);
  }

  // Search input
  safeAddListener('search-input', 'input', filterFiles);

  // View toggles
  safeAddListener('view-grid', 'click', () => {
    state.viewMode = 'grid';
    localStorage.setItem('viewMode', 'grid');
    updateViewButtons();
    processAndRenderFiles();
  });
  safeAddListener('view-list', 'click', () => {
    state.viewMode = 'list';
    localStorage.setItem('viewMode', 'list');
    updateViewButtons();
    processAndRenderFiles();
  });

  // Sort select
  const sortSelect = document.getElementById('sort-select');
  if (sortSelect) {
    sortSelect.addEventListener('change', (e) => {
      state.sortBy = e.target.value;
      localStorage.setItem('sortBy', e.target.value);
      processAndRenderFiles();
    });
  }

  // Filter select
  const filterSelect = document.getElementById('filter-select');
  if (filterSelect) {
    filterSelect.addEventListener('change', (e) => {
      state.filterType = e.target.value;
      processAndRenderFiles();
    });
  }

  // Initialize toolbar values
  initToolbar();

  // Add User Form
  safeAddListener('btn-add-user', 'click', () => openModal('modal-add-user'));
  safeAddListener('add-user-form', 'submit', handleAddUser);

  // Edit User Form & Visibility Toggle
  safeAddListener('edit-user-form', 'submit', handleEditUser);
  safeAddListener('btn-toggle-edit-password', 'click', toggleEditPasswordVisibility);
  safeAddListener('edit-avatar-input', 'change', handleAdminAvatarUpload);
  safeAddListener('btn-edit-remove-avatar', 'click', handleAdminAvatarDelete);

  // Permission management bindings
  safeAddListener('btn-add-rule', 'click', handleAddPermissionRule);
  safeAddListener('btn-save-permissions', 'click', handleSavePermissions);

  // Explorer refresh
  safeAddListener('btn-refresh-explorer', 'click', () => browsePath(state.currentPath));

  // Server Management bindings
  safeAddListener('btn-restart-server-service', 'click', () => runServerAction('restart-service'));
  safeAddListener('btn-reboot-server', 'click', () => runServerAction('reboot-host'));
  safeAddListener('btn-refresh-processes', 'click', () => {
    loadServerProcesses();
  });
  safeAddListener('btn-refresh-logs', 'click', () => {
    loadServerLogs();
  });

  // Bind server sub-tabs buttons
  document.querySelectorAll('.server-tab-btn').forEach(btn => {
    btn.addEventListener('click', (e) => {
      const targetTab = e.currentTarget.getAttribute('data-tab');
      switchSubTab(targetTab);
    });
  });

  // Server actions bindings
  safeAddListener('btn-refresh-docker', 'click', loadDockerContainers);
  safeAddListener('btn-refresh-services', 'click', loadsystemdServices);
  safeAddListener('btn-refresh-firewall', 'click', loadFirewallRules);
  safeAddListener('btn-refresh-cron', 'click', loadCronJobs);
  safeAddListener('btn-refresh-audit', 'click', loadAuditLogs);
  safeAddListener('btn-apt-search', 'click', () => {
    const searchInput = document.getElementById('apt-search-input');
    loadAptPackages(searchInput ? searchInput.value : '');
  });
  safeAddListener('btn-apt-upgrade-all', 'click', () => {
    runPackageAction('upgrade', '');
  });
  safeAddListener('btn-refresh-apt', 'click', () => {
    const searchInput = document.getElementById('apt-search-input');
    loadAptPackages(searchInput ? searchInput.value : '');
  });

  safeAddListener('firewall-rule-form', 'submit', handleAddFirewallRule);
  safeAddListener('cron-job-form', 'submit', handleAddCronJob);

  // Video close
  safeAddListener('btn-close-video', 'click', () => {
    closeAllMediaViewersSilently();
    if (window.location.hash.substring(1) !== state.currentPath) {
      window.location.hash = state.currentPath;
    }
  });

  // Image close
  safeAddListener('btn-close-image', 'click', () => {
    closeAllMediaViewersSilently();
    if (window.location.hash.substring(1) !== state.currentPath) {
      window.location.hash = state.currentPath;
    }
  });

  // Bind UI style card clicks dynamically
  document.querySelectorAll('.ui-style-card:not(.profile-ui-style-card)').forEach(card => {
    card.addEventListener('click', (e) => {
      const styleName = e.currentTarget.id.replace('ui-style-card-', '');
      setSystemUiStyle(styleName);
    });
  });

  // File/Folder Picker listeners
  safeAddListener('btn-browse-rule-path', 'click', openPickerModal);
  safeAddListener('btn-close-picker', 'click', closePickerModal);
  safeAddListener('btn-cancel-picker', 'click', closePickerModal);
  safeAddListener('btn-confirm-picker', 'click', confirmPickerSelection);
  safeAddListener('picker-drive-select', 'change', (e) => {
    loadPickerDirectory(e.target.value);
  });

  // Batch action bar & clipboard listeners
  safeAddListener('btn-paste', 'click', handlePasteClipboard);
  safeAddListener('btn-paste-dock', 'click', handlePasteClipboard);
  safeAddListener('btn-paste-dock-cancel', 'click', clearClipboard);
  safeAddListener('batch-select-all-checkbox', 'change', toggleSelectAll);
  safeAddListener('btn-batch-copy', 'click', handleBatchCopy);
  safeAddListener('btn-batch-move', 'click', handleBatchMove);
  safeAddListener('btn-batch-download', 'click', handleBatchDownload);
  safeAddListener('btn-batch-delete', 'click', handleBatchDelete);
  safeAddListener('btn-batch-clear', 'click', clearSelection);

  // Global keyboard shortcuts for clipboard (Copy / Cut / Paste / Clear)
  document.addEventListener('keydown', (e) => {
    // Ignore when user is typing in input or textarea
    if (['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement.tagName)) return;
    
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'c') {
      if (state.selectedPaths.size > 0) {
        e.preventDefault();
        handleBatchCopy();
      }
    } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'x') {
      if (state.selectedPaths.size > 0) {
        e.preventDefault();
        handleBatchMove();
      }
    } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'v') {
      if (state.clipboard && state.clipboard.paths && state.clipboard.paths.length > 0) {
        e.preventDefault();
        handlePasteClipboard();
      }
    } else if (e.key === 'Escape') {
      if (state.selectedPaths.size > 0) {
        clearSelection();
      } else if (state.clipboard && state.clipboard.paths && state.clipboard.paths.length > 0) {
        clearClipboard();
      }
    }
  });

  // Copy / Move Modal listeners
  safeAddListener('btn-close-copy-move', 'click', closeCopyMoveModal);
  safeAddListener('btn-cancel-copy-move', 'click', closeCopyMoveModal);
  safeAddListener('btn-confirm-copy-move', 'click', confirmCopyMove);
  safeAddListener('copy-move-drive-select', 'change', (e) => {
    loadCopyMoveDirectory(e.target.value);
  });
}

// AUTHENTICATION FLOWS
function showLogin() {
  document.body.classList.remove('logged-in');
  document.getElementById('login-container').classList.add('active');
  document.getElementById('dashboard-container').classList.remove('active');
  document.getElementById('login-error').innerText = '';
}

function showDashboard() {
  document.body.classList.add('logged-in');
  document.getElementById('login-container').classList.remove('active');
  document.getElementById('dashboard-container').classList.add('active');
  
  // Update user profile info in sidebar
  updateUserProfileUI();
  applyUserThemeAndStyle();

  // Toggle Admin / User Section Visibility
  if (state.user.role === 'admin') {
    document.querySelectorAll('.admin-only').forEach(el => el.style.display = 'flex');
    document.querySelectorAll('.user-only').forEach(el => el.style.display = 'none');
  } else {
    document.querySelectorAll('.admin-only').forEach(el => el.style.display = 'none');
    document.querySelectorAll('.user-only').forEach(el => el.style.display = 'flex');
  }

  switchPanel('explorer');
  loadRoots();
  initSse();
}

async function handleLogin(e) {
  if (e) {
    try { e.preventDefault(); } catch (err) {}
  }
  if (state.isLoggingIn) return;
  state.isLoggingIn = true;

  const usernameEl = document.getElementById('username');
  const passwordEl = document.getElementById('password');
  const errorEl = document.getElementById('login-error');

  if (errorEl) errorEl.innerText = '';
  
  try {
    const username = usernameEl ? usernameEl.value.trim() : '';
    const password = passwordEl ? passwordEl.value : '';

    if (!username || !password) {
      if (errorEl) errorEl.innerText = 'Username and password required';
      return;
    }

    const res = await apiCall('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({
        username: username,
        password: password
      })
    });

    state.token = res.token;
    state.user = res.user;
    
    const rememberMe = document.getElementById('remember-me') ? document.getElementById('remember-me').checked : true;
    if (rememberMe) {
      localStorage.setItem('token', res.token);
      localStorage.setItem('user', JSON.stringify(res.user));
      sessionStorage.removeItem('token');
      sessionStorage.removeItem('user');
    } else {
      sessionStorage.setItem('token', res.token);
      sessionStorage.setItem('user', JSON.stringify(res.user));
      localStorage.removeItem('token');
      localStorage.removeItem('user');
    }
    
    // Clear login inputs
    if (usernameEl) usernameEl.value = '';
    if (passwordEl) passwordEl.value = '';
    
    // Reset toggle to password mode
    const pwdInput = document.getElementById('password');
    if (pwdInput) pwdInput.type = 'password';
    const toggleBtn = document.getElementById('btn-toggle-login-password');
    if (toggleBtn) {
      toggleBtn.innerHTML = '<i data-lucide="eye"></i>';
      lucide.createIcons();
    }
    
    showDashboard();
  } catch (err) {
    if (errorEl) errorEl.innerText = err.message || 'Login failed';
  } finally {
    state.isLoggingIn = false;
  }
}
window.handleLogin = handleLogin;

function logout() {
  if (state.serverMetricsTimer) {
    clearInterval(state.serverMetricsTimer);
    state.serverMetricsTimer = null;
  }
  state.token = null;
  state.user = null;
  localStorage.removeItem('token');
  localStorage.removeItem('user');
  sessionStorage.removeItem('token');
  sessionStorage.removeItem('user');
  closeSse();
  window.location.hash = '';
  showLogin();
}

// PANEL NAVIGATION
function switchPanel(panelName) {
  closeAllMediaViewersSilently();

  // Clear any active metrics timer when switching panels
  if (state.serverMetricsTimer) {
    clearInterval(state.serverMetricsTimer);
    state.serverMetricsTimer = null;
  }

  document.querySelectorAll('.nav-item').forEach(btn => btn.classList.remove('active'));
  document.querySelectorAll('.mobile-nav-item').forEach(btn => btn.classList.remove('active'));
  document.querySelectorAll('.content-panel').forEach(panel => panel.classList.remove('active'));

  if (panelName === 'explorer') {
    document.getElementById('nav-explorer').classList.add('active');
    const mobExplorer = document.getElementById('mobile-nav-explorer');
    if (mobExplorer) mobExplorer.classList.add('active');
    document.getElementById('panel-explorer').classList.add('active');
    document.getElementById('explorer-actions').style.display = '';
    if (state.currentPath) {
      browsePath(state.currentPath);
    }
  } else if (panelName === 'users') {
    document.getElementById('nav-users').classList.add('active');
    const mobUsers = document.getElementById('mobile-nav-users');
    if (mobUsers) mobUsers.classList.add('active');
    document.getElementById('panel-users').classList.add('active');
    document.getElementById('explorer-actions').style.display = 'none';
    loadUsers();
    loadStorageAnalysis();
  } else if (panelName === 'server') {
    document.getElementById('nav-server').classList.add('active');
    const mobServer = document.getElementById('mobile-nav-server');
    if (mobServer) mobServer.classList.add('active');
    document.getElementById('panel-server').classList.add('active');
    document.getElementById('explorer-actions').style.display = 'none';
    
    // Switch to first sub-tab by default
    switchSubTab('tab-metrics');

    // Start auto-refresh polling for metrics and processes list every 1 second
    state.serverMetricsTimer = setInterval(() => {
      const activeSubTab = document.querySelector('.server-tab-content.active');
      if (activeSubTab) {
        refreshActiveSubTab(activeSubTab.id);
      }
    }, 1000);
  } else if (panelName === 'recycle-bin') {
    document.getElementById('nav-recycle-bin').classList.add('active');
    const mobRecycle = document.getElementById('mobile-nav-recycle-bin');
    if (mobRecycle) mobRecycle.classList.add('active');
    document.getElementById('panel-recycle-bin').classList.add('active');
    document.getElementById('explorer-actions').style.display = 'none';
    loadRecycleBin();
  } else if (panelName === 'profile') {
    const navProfile = document.getElementById('nav-profile');
    if (navProfile) navProfile.classList.add('active');
    const mobProfile = document.getElementById('mobile-nav-profile');
    if (mobProfile) mobProfile.classList.add('active');
    document.getElementById('panel-profile').classList.add('active');
    document.getElementById('explorer-actions').style.display = 'none';
    
    document.getElementById('profile-username').value = state.user ? state.user.username : '';
    document.getElementById('profile-password').value = '';
    document.getElementById('profile-password').type = 'password';
    document.getElementById('profile-current-password-display').innerText = (state.user && state.user.plainPassword) ? state.user.plainPassword : (state.user ? state.user.username : 'None');
    document.getElementById('profile-error').innerText = '';
    document.getElementById('profile-success').style.display = 'none';
  }
}

// EXPLORER FUNCTIONALITY
function isFilePath(path) {
  if (!path) return false;
  const lastSlashIndex = path.lastIndexOf('/');
  const lastPart = lastSlashIndex !== -1 ? path.substring(lastSlashIndex + 1) : path;
  return lastPart.includes('.') && lastPart.split('.').pop().match(/^[a-zA-Z0-9]{2,5}$/);
}

function findRootForPath(targetPath) {
  if (!targetPath) return null;
  const sortedRoots = [...state.roots].sort((a, b) => b.path.length - a.path.length);
  return sortedRoots.find(r => targetPath === r.path || targetPath.startsWith(r.path + '/')) || null;
}

async function loadRoots() {
  try {
    const roots = await apiCall('/api/files/roots');
    state.roots = roots;
    renderRoots();
    
    if (roots.length > 0) {
      const hashPath = window.location.hash.substring(1) || '';
      const decoded = hashPath ? decodeURIComponent(hashPath) : null;
      
      if (decoded) {
        if (isFilePath(decoded)) {
          const lastSlashIndex = decoded.lastIndexOf('/');
          const parentPath = lastSlashIndex !== -1 ? decoded.substring(0, lastSlashIndex) : '';
          const fileName = lastSlashIndex !== -1 ? decoded.substring(lastSlashIndex + 1) : decoded;
          
          const matchedRoot = findRootForPath(parentPath);
          if (matchedRoot) {
            state.currentRoot = matchedRoot;
            state.currentPath = parentPath;
            state.autoOpenFile = fileName;
            renderRoots();
            browsePath(parentPath);
          } else {
            selectRoot(roots[0]);
          }
        } else {
          const matchedRoot = findRootForPath(decoded);
          if (matchedRoot) {
            state.currentRoot = matchedRoot;
            state.currentPath = decoded;
            renderRoots();
            browsePath(decoded);
          } else {
            selectRoot(roots[0]);
          }
        }
      } else {
        selectRoot(roots[0]);
      }
    } else {
      document.getElementById('files-grid-container').innerHTML = '';
      document.getElementById('empty-state').style.display = 'flex';
    }
  } catch (err) {
    console.error('Failed to load root folders:', err);
  }
}

function renderRoots() {
  const container = document.getElementById('roots-container');
  if (!container) return;
  container.innerHTML = '';

  state.roots.forEach(root => {
    const badge = document.createElement('div');
    badge.className = 'root-badge';
    if (state.currentRoot && state.currentRoot.path === root.path) {
      badge.classList.add('active');
    }
    
    let rootName = root.name;
    if (rootName === 'Home root') rootName = 'Home';
    else if (rootName === 'Storage root') rootName = 'Storage';
    else if (rootName === 'HDD root') rootName = 'HDD';
    else if (rootName === 'Google Drive') rootName = 'Google Drive';

    // Shorten Google Drive to GDrive on small viewports
    if (window.innerWidth <= 576 && rootName === 'Google Drive') {
      rootName = 'GDrive';
    }

    badge.innerHTML = `<i data-lucide="hard-drive"></i> <span>${rootName}</span>`;
    badge.addEventListener('click', () => selectRoot(root));
    container.appendChild(badge);
  });
  if (typeof lucide !== 'undefined') lucide.createIcons();
}

function selectRoot(root) {
  state.currentRoot = root;
  state.currentPath = root.path;
  renderRoots();
  browsePath(root.path);
}

function updateUploadActionsVisibility() {
  const isWritable = state.user.role === 'admin' || (state.currentRoot && state.currentRoot.allowWrite);
  const displayStyle = isWritable ? 'flex' : 'none';
  
  const btnNewFolder = document.getElementById('btn-new-folder');
  const btnUpload = document.getElementById('btn-upload-trigger');
  const btnUploadFolder = document.getElementById('btn-upload-folder-trigger');
  
  if (btnNewFolder) btnNewFolder.style.display = displayStyle;
  if (btnUpload) btnUpload.style.display = displayStyle;
  if (btnUploadFolder) btnUploadFolder.style.display = displayStyle;
}

async function browsePath(targetPath) {
  state.currentPath = targetPath;
  state.selectedPaths.clear();
  updateBatchActionBar();
  updatePasteButton();
  document.getElementById('search-input').value = ''; // clear search
  updateUploadActionsVisibility();
  
  const sizeDisplay = document.getElementById('open-folder-size');
  if (sizeDisplay) sizeDisplay.style.display = 'none';
  
  if (window.location.hash.substring(1) !== targetPath) {
    window.location.hash = targetPath;
  }
  
  try {
    const res = await apiCall(`/api/files/browse?path=${encodeURIComponent(targetPath)}`);
    state.files = res.files;
    processAndRenderFiles();
    renderBreadcrumbs();
    
    if (sizeDisplay) {
      const sizeValue = document.getElementById('open-folder-size-value');
      if (sizeValue && res.folderSize !== undefined && res.folderSize !== null) {
        sizeValue.innerText = formatBytes(res.folderSize);
        sizeDisplay.style.display = 'flex';
        if (typeof lucide !== 'undefined') lucide.createIcons();
      }
    }
  } catch (err) {
    console.error('Failed to browse path:', err);
    document.getElementById('files-grid-container').innerHTML = `<div class="error-message">${err.message}</div>`;
  }
}

function formatDate(timestamp) {
  if (!timestamp) return '';
  const date = new Date(timestamp);
  return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function initToolbar() {
  const sortSelect = document.getElementById('sort-select');
  if (sortSelect) {
    sortSelect.value = state.sortBy;
  }

  const filterSelect = document.getElementById('filter-select');
  if (filterSelect) {
    filterSelect.value = state.filterType;
  }

  updateViewButtons();
  updateBatchActionBar();
  updatePasteButton();
}

function updateViewButtons() {
  const btnGrid = document.getElementById('view-grid');
  const btnList = document.getElementById('view-list');
  if (!btnGrid || !btnList) return;

  if (state.viewMode === 'list') {
    btnGrid.classList.remove('active');
    btnList.classList.add('active');
  } else {
    btnGrid.classList.add('active');
    btnList.classList.remove('active');
  }
}

function processAndRenderFiles() {
  let files = [...state.files];

  // 1. Search filter
  const searchVal = document.getElementById('search-input').value.toLowerCase();
  if (searchVal) {
    files = files.filter(f => f.name.toLowerCase().includes(searchVal));
  }

  // 2. Type filter
  if (state.filterType !== 'all') {
    files = files.filter(file => {
      let category = 'other';
      const ext = file.name.split('.').pop().toLowerCase();
      if (!file.isFile) {
        category = 'folder';
      } else if (['mp4', 'mkv', 'webm', 'avi', 'mov', 'flv', 'wmv', 'm4v', 'ts', '3gp'].includes(ext)) {
        category = 'video';
      } else if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp', 'ico', 'tiff'].includes(ext)) {
        category = 'image';
      } else if (['mp3', 'wav', 'ogg', 'flac', 'm4a', 'aac', 'opus', 'wma', 'alac'].includes(ext)) {
        category = 'audio';
      } else if (['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt', 'rtf', 'odt', 'ods', 'odp', 'epub', 'srt', 'ass', 'vtt', 'md', 'json', 'xml', 'csv', 'log'].includes(ext)) {
        category = 'document';
      } else if (['zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz', 'iso', 'tgz'].includes(ext)) {
        category = 'archive';
      }
      return category === state.filterType;
    });
  }

  // 3. Sort files
  const [field, direction] = state.sortBy.split('-');
  files.sort((a, b) => {
    let aIsFile = a.isFile;
    let bIsFile = b.isFile;
    if (aIsFile !== bIsFile) {
      return aIsFile ? 1 : -1; // folders first
    }

    let valA, valB;
    if (field === 'name') {
      valA = a.name.toLowerCase();
      valB = b.name.toLowerCase();
      return direction === 'asc' ? valA.localeCompare(valB) : valB.localeCompare(valA);
    } else if (field === 'size') {
      valA = a.size;
      valB = b.size;
    } else if (field === 'mtime') {
      valA = a.mtime || 0;
      valB = b.mtime || 0;
    }

    if (valA < valB) return direction === 'asc' ? -1 : 1;
    if (valA > valB) return direction === 'asc' ? 1 : -1;
    return 0;
  });

  renderFiles(files);

  if (state.autoOpenFile) {
    const fileToOpen = state.files.find(f => f.name === state.autoOpenFile);
    const fileName = state.autoOpenFile;
    state.autoOpenFile = null;
    if (fileToOpen) {
      const ext = fileToOpen.name.split('.').pop().toLowerCase();
      let category = 'file';
      if (['mp4', 'mkv', 'webm', 'avi', 'mov'].includes(ext)) category = 'video';
      else if (['png', 'jpg', 'jpeg', 'gif', 'webp'].includes(ext)) category = 'image';
      else if (['mp3', 'wav', 'ogg', 'flac', 'm4a'].includes(ext)) category = 'audio';
      
      const filePath = `${state.currentPath}/${fileToOpen.name}`;
      openMedia(filePath, fileName, category);
    }
  }
}

function renderFiles(files) {
  const grid = document.getElementById('files-grid-container');
  const emptyState = document.getElementById('empty-state');
  grid.innerHTML = '';

  if (files.length === 0) {
    emptyState.style.display = 'flex';
    updateBatchActionBar();
    return;
  }
  emptyState.style.display = 'none';

  if (state.viewMode === 'list') {
    grid.classList.add('list-view');
  } else {
    grid.classList.remove('list-view');
  }

  if (state.selectedPaths.size > 0) {
    grid.classList.add('has-selection');
  } else {
    grid.classList.remove('has-selection');
  }

  files.forEach(file => {
    const card = document.createElement('div');
    card.className = 'file-card';
    const filePath = `${state.currentPath}/${file.name}`;
    const isSelected = state.selectedPaths.has(filePath);
    if (isSelected) {
      card.classList.add('selected');
    }
    
    // Check if item is marked for cut/move
    if (state.clipboard && state.clipboard.action === 'move' && state.clipboard.paths && state.clipboard.paths.includes(filePath)) {
      card.classList.add('cut-item');
    }
    
    // Determine card category class and icon
    let category = 'file';
    let icon = 'file-text';
    const ext = file.name.split('.').pop().toLowerCase();
    
    if (!file.isFile) {
      category = 'folder';
      icon = 'folder';
    } else if (['mp4', 'mkv', 'webm', 'avi', 'mov', 'flv', 'wmv', 'm4v', 'ts', '3gp'].includes(ext)) {
      category = 'video';
      icon = 'video';
    } else if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp', 'ico', 'tiff'].includes(ext)) {
      category = 'image';
      icon = 'image';
    } else if (['mp3', 'wav', 'ogg', 'flac', 'm4a', 'aac', 'opus', 'wma', 'alac'].includes(ext)) {
      category = 'audio';
      icon = 'music';
    } else if (['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt', 'rtf', 'odt', 'ods', 'odp', 'epub', 'srt', 'ass', 'vtt', 'md', 'json', 'xml', 'csv', 'log'].includes(ext)) {
      category = 'document';
      icon = 'file-text';
    } else if (['zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz', 'iso', 'tgz'].includes(ext)) {
      category = 'archive';
      icon = 'archive';
    }

    card.classList.add(category);
    
    const srcUrl = `/api/files/stream?path=${encodeURIComponent(filePath)}&token=${state.token}`;
    
    // Size formatting helper
    const formattedSize = file.isFile ? formatBytes(file.size) : '';
    const formattedDate = file.mtime ? formatDate(file.mtime) : '';

    let iconHtml = `<i data-lucide="${icon}"></i>`;
    if (category === 'image' && state.viewMode === 'grid') {
      iconHtml = `<img src="${srcUrl}" style="width: 100%; height: 100%; object-fit: cover; border-radius: var(--radius-md);" alt="${file.name}">`;
    }

    card.innerHTML = `
      <div class="file-checkbox-wrapper">
        <input type="checkbox" class="file-checkbox" data-path="${filePath.replace(/"/g, '&quot;')}" ${isSelected ? 'checked' : ''} onclick="event.stopPropagation(); toggleCardSelection('${filePath.replace(/'/g, "\\'")}', this.checked);">
      </div>
      <div class="file-icon-wrapper" ${category === 'image' && state.viewMode === 'grid' ? 'style="overflow: hidden; padding: 0;"' : ''}>
        ${iconHtml}
      </div>
      <div class="file-card-info">
        <div class="file-name" title="${file.name}">${file.name}</div>
        <div class="file-meta-size">${file.isFile ? formattedSize : 'Folder'}</div>
        <div class="file-meta-date">${formattedDate}</div>
      </div>
      <div class="file-actions">
        <button class="btn-card-action btn-copy" onclick="handleCopySingle(event, '${filePath.replace(/'/g, "\\'")}')" title="Copy">
          <i data-lucide="copy"></i>
        </button>
        <button class="btn-card-action btn-move" onclick="handleMoveSingle(event, '${filePath.replace(/'/g, "\\'")}')" title="Move">
          <i data-lucide="folder-input"></i>
        </button>
        ${file.isFile ? `
          <button class="btn-card-action btn-download" onclick="handleDownloadFile(event, '${filePath.replace(/'/g, "\\'")}')" title="Download">
            <i data-lucide="download"></i>
          </button>
        ` : `
          <button class="btn-card-action btn-download" onclick="handleDownloadFolder(event, '${filePath.replace(/'/g, "\\'")}')" title="Download Folder as ZIP">
            <i data-lucide="download"></i>
          </button>
        `}
        <button class="btn-card-action btn-rename" onclick="handleRenameFile(event, '${filePath.replace(/'/g, "\\'")}', '${file.name.replace(/'/g, "\\'")}')" title="Rename">
          <i data-lucide="edit-3"></i>
        </button>
        <button class="btn-card-action" onclick="handleDeleteFile(event, '${filePath.replace(/'/g, "\\'")}')" title="Delete">
          <i data-lucide="trash-2"></i>
        </button>
      </div>
    `;

    // Click handler: Double-click / Click to open
    card.addEventListener('click', (e) => {
      // Prevent action button click or checkbox click from triggering card click
      if (e.target.closest('.btn-card-action') || e.target.closest('.file-checkbox-wrapper')) return;
      
      if (e.ctrlKey || e.metaKey) {
        toggleCardSelection(filePath, !state.selectedPaths.has(filePath));
        return;
      }

      if (!file.isFile) {
        browsePath(filePath);
      } else {
        openMedia(filePath, file.name, category);
      }
    });

    grid.appendChild(card);
  });
  
  updateBatchActionBar();
  lucide.createIcons();
}

function renderBreadcrumbs() {
  const container = document.getElementById('breadcrumbs-container');
  const rootSeparator = document.getElementById('breadcrumb-root-separator');
  if (!container) return;
  container.innerHTML = '';
  
  if (!state.currentRoot) {
    if (rootSeparator) rootSeparator.style.display = 'none';
    return;
  }

  const rootPath = state.currentRoot.path;
  
  if (state.currentPath === rootPath) {
    if (rootSeparator) rootSeparator.style.display = 'none';
    return;
  }

  if (rootSeparator) rootSeparator.style.display = 'flex';

  // Get subpath relative to root path
  const relativePart = state.currentPath.substring(rootPath.length);
  const parts = relativePart.split('/').filter(p => p !== '');
  
  let accumulatedPath = rootPath;
  
  parts.forEach((part, index) => {
    if (index > 0) {
      // Separator between subfolders
      const separator = document.createElement('span');
      separator.className = 'breadcrumb-separator';
      separator.innerHTML = '<i data-lucide="chevron-right"></i>';
      container.appendChild(separator);
    }

    accumulatedPath += '/' + part;
    
    const item = document.createElement('span');
    item.className = 'breadcrumb-item';
    if (index === parts.length - 1) {
      item.classList.add('active');
    }
    item.innerText = part;
    
    const clickPath = accumulatedPath; // lock closure value
    item.addEventListener('click', () => {
      if (index < parts.length - 1) {
        browsePath(clickPath);
      }
    });
    
    container.appendChild(item);
  });
  lucide.createIcons();
}

function filterFiles() {
  processAndRenderFiles();
}

// MEDIA HANDLERS
function openMedia(filePath, fileName, category) {
  if (window.location.hash.substring(1) !== filePath) {
    window.location.hash = filePath;
  }
  if (category === 'video') {
    document.getElementById('video-player-title').innerText = fileName;
    const player = document.getElementById('html5-video-player');
    const errorBanner = document.getElementById('video-error-banner');
    
    if (errorBanner) errorBanner.style.display = 'none';
    player.style.display = 'block';
    
    const relativeStreamUrl = `/api/files/stream-media/${safeBase64Encode(filePath)}?token=${state.token}`;
    player.src = relativeStreamUrl;

    if (state.videoWatchdog) {
      clearTimeout(state.videoWatchdog);
    }
    
    // 4.0s watchdog to detect if video gets stuck at buffering due to unsupported decoder
    state.videoWatchdog = setTimeout(() => {
      if (player.currentTime === 0 && !player.paused) {
        console.warn('Watchdog timed out. Video is stuck in loading state (likely unsupported video/audio codec). Showing external options.');
        player.style.display = 'none';
        if (errorBanner) {
          errorBanner.style.display = 'block';
          lucide.createIcons();
        }
      }
    }, 4000);

    player.onplaying = () => {
      if (state.videoWatchdog) {
        clearTimeout(state.videoWatchdog);
        state.videoWatchdog = null;
      }
    };

    player.ontimeupdate = () => {
      if (player.currentTime > 0 && state.videoWatchdog) {
        clearTimeout(state.videoWatchdog);
        state.videoWatchdog = null;
      }
    };

    player.onerror = () => {
      console.warn('HTML5 video player encountered an error (likely unsupported codec). Showing external stream options.');
      if (state.videoWatchdog) {
        clearTimeout(state.videoWatchdog);
        state.videoWatchdog = null;
      }
      player.style.display = 'none';
      if (errorBanner) {
        errorBanner.style.display = 'block';
        lucide.createIcons();
      }
    };
    
    const downloadBtn = document.getElementById('btn-download-video');
    downloadBtn.href = `/api/files/download?path=${encodePathQuery(filePath)}&token=${state.token}`;

    // Configure VLC Streaming Options
    const absoluteStreamUrl = window.location.origin + relativeStreamUrl;

    // 1. M3U playlist file generation
    const m3uBtn = document.getElementById('btn-stream-vlc-m3u');
    const m3uContent = `#EXTM3U\n#EXTINF:-1,${fileName}\n${absoluteStreamUrl}`;
    const blob = new Blob([m3uContent], { type: 'application/x-mpegurl' });
    m3uBtn.href = URL.createObjectURL(blob);
    m3uBtn.download = fileName.substring(0, fileName.lastIndexOf('.')) + ".m3u";



    // 3. Copy Stream URL action
    const copyBtn = document.getElementById('btn-copy-stream-link');
    copyBtn.onclick = async () => {
      try {
        await navigator.clipboard.writeText(absoluteStreamUrl);
        const originalHtml = copyBtn.innerHTML;
        copyBtn.innerHTML = '<i data-lucide="check" style="width: 16px; height: 16px;"></i> <span>Copied!</span>';
        lucide.createIcons();
        setTimeout(() => {
          copyBtn.innerHTML = originalHtml;
          lucide.createIcons();
        }, 2000);
      } catch (err) {
        alert('Could not copy link: ' + absoluteStreamUrl);
      }
    };



    openModal('modal-video-player');
  } else if (category === 'image') {
    document.getElementById('image-viewer-title').innerText = fileName;
    const img = document.getElementById('viewer-img');
    
    const srcUrl = `/api/files/stream?path=${encodeURIComponent(filePath)}&token=${state.token}`;
    img.src = srcUrl;
    
    const downloadBtn = document.getElementById('btn-download-image');
    downloadBtn.href = `/api/files/download?path=${encodeURIComponent(filePath)}&token=${state.token}`;
    
    openModal('modal-image-viewer');
  } else {
    // Just trigger standard download
    window.open(`/api/files/download?path=${encodeURIComponent(filePath)}&token=${state.token}`, '_blank');
  }
}



// FILE OPERATIONS
async function handleCreateFolder(e) {
  e.preventDefault();
  const nameInput = document.getElementById('folder-name-input');
  const name = nameInput.value.trim();
  
  if (!name) return;

  try {
    await apiCall('/api/files/mkdir', {
      method: 'POST',
      body: JSON.stringify({
        path: state.currentPath,
        name: name
      })
    });
    
    closeModal('modal-new-folder');
    nameInput.value = '';
    browsePath(state.currentPath);
  } catch (err) {
    alert(`Failed to create directory: ${err.message}`);
  }
}

async function handleDeleteFile(e, filePath) {
  e.stopPropagation(); // prevent card click
  const filename = filePath.split('/').pop();
  if (!confirm(`Are you sure you want to delete ${filename}?`)) return;

  try {
    await apiCall(`/api/files/delete?path=${encodeURIComponent(filePath)}`, {
      method: 'DELETE'
    });
    browsePath(state.currentPath);
  } catch (err) {
    alert(`Failed to delete: ${err.message}`);
  }
}

async function handleRenameFile(e, filePath, oldName) {
  e.stopPropagation(); // prevent card click
  const newName = prompt(`Enter new name for "${oldName}":`, oldName);
  if (newName === null) return; // user cancelled
  
  const trimmed = newName.trim();
  if (!trimmed) {
    alert("Name cannot be empty");
    return;
  }
  if (trimmed === oldName) return; // no change

  try {
    const res = await apiCall(`/api/files/rename?path=${encodeURIComponent(filePath)}&newName=${encodeURIComponent(trimmed)}`, {
      method: 'POST'
    });
    if (res.success) {
      browsePath(state.currentPath);
    } else {
      alert(`Failed to rename: ${res.error || 'Unknown error'}`);
    }
  } catch (err) {
    alert(`Failed to rename: ${err.message}`);
  }
}
window.handleRenameFile = handleRenameFile;

function handleDownloadFile(e, filePath) {
  e.stopPropagation(); // prevent card click
  window.open(`/api/files/download?path=${encodeURIComponent(filePath)}&token=${state.token}`, '_blank');
}
window.handleDownloadFile = handleDownloadFile;

function handleDownloadFolder(e, filePath) {
  if (e) { e.stopPropagation(); }
  window.open(`/api/files/download-folder?path=${encodeURIComponent(filePath)}&token=${state.token}`, '_blank');
}
window.handleDownloadFolder = handleDownloadFolder;

// ============================================================
// TOAST NOTIFICATIONS & SELECTION & BATCH ACTIONS & COPY/MOVE
// ============================================================

function showToast(message, type = 'info') {
  let container = document.getElementById('toast-container');
  if (!container) {
    container = document.createElement('div');
    container.id = 'toast-container';
    container.style.cssText = 'position: fixed; bottom: 24px; right: 24px; z-index: 9999; display: flex; flex-direction: column; gap: 8px; pointer-events: none;';
    document.body.appendChild(container);
  }
  const toast = document.createElement('div');
  const bg = type === 'error' ? 'rgba(239, 68, 68, 0.95)' : type === 'success' ? 'rgba(16, 185, 129, 0.95)' : 'rgba(24, 24, 37, 0.95)';
  const border = type === 'error' ? '#ef4444' : type === 'success' ? '#10b981' : 'var(--primary)';
  toast.style.cssText = `background: ${bg}; color: #fff; border: 1px solid ${border}; border-radius: 8px; padding: 12px 18px; font-size: 13px; font-weight: 500; box-shadow: 0 8px 24px rgba(0,0,0,0.4); pointer-events: auto; display: flex; align-items: center; gap: 8px; animation: slideDown 0.25s ease-out;`;
  toast.innerHTML = `<span>${message}</span>`;
  container.appendChild(toast);
  setTimeout(() => {
    toast.style.opacity = '0';
    toast.style.transform = 'translateY(10px)';
    toast.style.transition = 'all 0.3s ease';
    setTimeout(() => toast.remove(), 300);
  }, 3500);
}
window.showToast = showToast;

function toggleCardSelection(filePath, isChecked) {
  if (isChecked) {
    state.selectedPaths.add(filePath);
  } else {
    state.selectedPaths.delete(filePath);
  }
  updateBatchActionBar();
  
  // Update card styling directly without re-rendering everything
  const cards = document.querySelectorAll('.file-card');
  cards.forEach(card => {
    const cb = card.querySelector('.file-checkbox');
    if (cb && cb.getAttribute('data-path') === filePath) {
      cb.checked = isChecked;
      if (isChecked) card.classList.add('selected');
      else card.classList.remove('selected');
    }
  });

  const grid = document.getElementById('files-grid-container');
  if (grid) {
    if (state.selectedPaths.size > 0) grid.classList.add('has-selection');
    else grid.classList.remove('has-selection');
  }
}
window.toggleCardSelection = toggleCardSelection;

function toggleSelectAll(e) {
  const isChecked = e.target.checked;
  if (!isChecked) {
    state.selectedPaths.clear();
  } else {
    state.files.forEach(f => {
      const p = `${state.currentPath}/${f.name}`;
      state.selectedPaths.add(p);
    });
  }
  processAndRenderFiles();
  updateBatchActionBar();
}
window.toggleSelectAll = toggleSelectAll;

function clearSelection() {
  state.selectedPaths.clear();
  processAndRenderFiles();
  updateBatchActionBar();
}
window.clearSelection = clearSelection;

function updateBatchActionBar() {
  const bar = document.getElementById('batch-action-bar');
  const countEl = document.getElementById('batch-selected-count');
  const selectAllCb = document.getElementById('batch-select-all-checkbox');
  
  const count = state.selectedPaths.size;
  if (countEl) {
    countEl.innerText = `${count} item${count === 1 ? '' : 's'} selected`;
  }
  
  if (bar) {
    if (count > 0) {
      bar.classList.add('active');
      bar.style.display = 'inline-flex';
      if (typeof lucide !== 'undefined') lucide.createIcons();
    } else {
      bar.classList.remove('active');
      bar.style.display = 'none';
    }
  }

  if (selectAllCb) {
    const totalFiles = state.files.length;
    selectAllCb.checked = totalFiles > 0 && count === totalFiles;
    selectAllCb.indeterminate = count > 0 && count < totalFiles;
  }
}
window.updateBatchActionBar = updateBatchActionBar;

function updatePasteButton() {
  const topBtn = document.getElementById('btn-paste');
  const topLabel = document.getElementById('btn-paste-label');
  const dock = document.getElementById('clipboard-dock');
  const dockStatus = document.getElementById('clipboard-status-label');
  const dockBtnText = document.getElementById('btn-paste-dock-text');
  
  const count = state.clipboard && state.clipboard.paths ? state.clipboard.paths.length : 0;
  const isMove = state.clipboard && state.clipboard.action === 'move';
  
  if (count > 0) {
    const actionText = isMove ? 'Move' : 'Paste';
    const statusText = `${count} item${count === 1 ? '' : 's'} (${isMove ? 'Cut' : 'Copied'})`;
    
    if (topBtn && topLabel) {
      topLabel.innerText = `${actionText} (${count})`;
      topBtn.style.display = 'inline-flex';
    }
    
    if (dock) {
      if (dockStatus) dockStatus.innerText = statusText;
      if (dockBtnText) dockBtnText.innerText = `${actionText} Here`;
      dock.classList.add('active');
      dock.style.display = 'inline-flex';
    }
    if (typeof lucide !== 'undefined') lucide.createIcons();
  } else {
    if (topBtn) topBtn.style.display = 'none';
    if (dock) {
      dock.classList.remove('active');
      dock.style.display = 'none';
    }
  }
}
window.updatePasteButton = updatePasteButton;

function clearClipboard() {
  state.clipboard = { action: null, paths: [] };
  updatePasteButton();
  renderExplorer();
  showToast('Clipboard cleared', 'info');
}
window.clearClipboard = clearClipboard;

// COPY & MOVE ACTIONS (Direct Clipboard Workflow)
function handleCopySingle(e, filePath) {
  if (e) { e.preventDefault(); e.stopPropagation(); }
  state.clipboard = { action: 'copy', paths: [filePath] };
  state.selectedPaths.clear();
  updateBatchActionBar();
  updatePasteButton();
  renderExplorer();
  const filename = filePath.split('/').pop();
  showToast(`Copied "${filename}" to clipboard. Navigate to destination folder and click Paste.`, 'info');
}
window.handleCopySingle = handleCopySingle;

function handleMoveSingle(e, filePath) {
  if (e) { e.preventDefault(); e.stopPropagation(); }
  state.clipboard = { action: 'move', paths: [filePath] };
  state.selectedPaths.clear();
  updateBatchActionBar();
  updatePasteButton();
  renderExplorer();
  const filename = filePath.split('/').pop();
  showToast(`Cut "${filename}" to clipboard. Navigate to destination folder and click Move Here.`, 'info');
}
window.handleMoveSingle = handleMoveSingle;

function handleBatchCopy() {
  const paths = Array.from(state.selectedPaths);
  if (paths.length === 0) return;
  state.clipboard = { action: 'copy', paths: paths };
  state.selectedPaths.clear();
  updateBatchActionBar();
  updatePasteButton();
  renderExplorer();
  showToast(`Copied ${paths.length} item(s) to clipboard. Navigate to destination folder and click Paste.`, 'info');
}
window.handleBatchCopy = handleBatchCopy;

function handleBatchMove() {
  const paths = Array.from(state.selectedPaths);
  if (paths.length === 0) return;
  state.clipboard = { action: 'move', paths: paths };
  state.selectedPaths.clear();
  updateBatchActionBar();
  updatePasteButton();
  renderExplorer();
  showToast(`Cut ${paths.length} item(s) to clipboard. Navigate to destination folder and click Move Here.`, 'info');
}
window.handleBatchMove = handleBatchMove;

async function handleBatchDelete() {
  const paths = Array.from(state.selectedPaths);
  if (paths.length === 0) return;

  const count = paths.length;
  if (!confirm(`Are you sure you want to move ${count} item${count === 1 ? '' : 's'} to the Recycle Bin?`)) {
    return;
  }

  try {
    const res = await apiCall('/api/files/batch-delete', {
      method: 'POST',
      body: JSON.stringify({ paths })
    });
    if (res.success) {
      showToast(`Moved ${res.deletedCount || count} item(s) to Recycle Bin`, 'success');
      state.selectedPaths.clear();
      updateBatchActionBar();
      browsePath(state.currentPath);
    } else {
      showToast(`Delete completed with some errors: ${(res.errors || []).join(', ')}`, 'error');
      state.selectedPaths.clear();
      updateBatchActionBar();
      browsePath(state.currentPath);
    }
  } catch (err) {
    showToast(`Failed to delete items: ${err.message}`, 'error');
  }
}
window.handleBatchDelete = handleBatchDelete;

async function handleBatchDownload() {
  const paths = Array.from(state.selectedPaths);
  if (paths.length === 0) return;

  if (paths.length === 1) {
    const p = paths[0];
    const fileObj = state.files.find(f => `${state.currentPath}/${f.name}` === p);
    if (fileObj && !fileObj.isFile) {
      handleDownloadFolder(null, p);
    } else {
      handleDownloadFile(null, p);
    }
    return;
  }

  // Multi-item ZIP download
  showToast('Preparing ZIP download for selected items...', 'info');
  try {
    const response = await fetch('/api/files/download-batch', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${state.token}`
      },
      body: JSON.stringify({ paths })
    });
    if (!response.ok) throw new Error('Download failed');
    const blob = await response.blob();
    const url = window.URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `sakura_batch_${Date.now()}.zip`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    window.URL.revokeObjectURL(url);
  } catch (err) {
    showToast(`Failed to download: ${err.message}`, 'error');
  }
}
window.handleBatchDownload = handleBatchDownload;

async function handlePasteClipboard() {
  if (!state.clipboard || !state.clipboard.paths || state.clipboard.paths.length === 0) return;

  const { action, paths } = state.clipboard;
  const destination = state.currentPath;
  const endpoint = action === 'move' ? '/api/files/move' : '/api/files/copy';
  const taskId = 'task_' + Date.now() + '_' + Math.random().toString(36).substring(2, 7);

  // Check if user is trying to move into the exact same folder
  if (action === 'move') {
    const isSameDir = paths.every(p => {
      const parent = p.substring(0, p.lastIndexOf('/')) || '/';
      return parent === destination;
    });
    if (isSameDir) {
      showToast('Items are already in this destination directory.', 'warning');
      return;
    }
  }

  showToast(`${action === 'move' ? 'Moving' : 'Copying'} ${paths.length} item(s)...`, 'info');
  updateFileOpProgressUI({
    taskId: taskId,
    action: action,
    currentFile: 'Preparing...',
    copiedBytes: 0,
    totalBytes: 0,
    copiedFiles: 0,
    totalFiles: paths.length,
    percent: 0,
    speed: '',
    completed: false
  });

  try {
    const res = await apiCall(endpoint, {
      method: 'POST',
      body: JSON.stringify({
        sources: paths,
        destination: destination,
        overwrite: false,
        taskId: taskId
      })
    });

    if (res.success) {
      showToast(`Successfully ${action === 'move' ? 'moved' : 'copied'} items!`, 'success');
      state.clipboard = { action: null, paths: [] };
      updatePasteButton();
      browsePath(state.currentPath);
    } else {
      showToast(`Operation completed with errors: ${(res.errors || []).join('; ')}`, 'error');
      state.clipboard = { action: null, paths: [] };
      updatePasteButton();
      browsePath(state.currentPath);
    }
  } catch (err) {
    showToast(`Failed to paste items: ${err.message}`, 'error');
  }
}
window.handlePasteClipboard = handlePasteClipboard;

// COPY / MOVE DESTINATION PICKER MODAL
function openCopyMoveModal(action, paths) {
  state.copyMoveAction = action; // 'copy' | 'move'
  state.copyMoveSources = paths;
  state.copyMoveTarget = state.currentPath || (state.roots.length > 0 ? state.roots[0].path : '/home/sakura');

  const modal = document.getElementById('modal-copy-move');
  const title = document.getElementById('modal-copy-move-title');
  const summary = document.getElementById('modal-copy-move-summary');
  const confirmBtnText = document.getElementById('btn-confirm-copy-move-text');
  const confirmBtn = document.getElementById('btn-confirm-copy-move');
  const cancelBtn = document.getElementById('btn-cancel-copy-move');
  const progressContainer = document.getElementById('copy-move-progress-container');

  if (title) title.innerText = action === 'move' ? 'Move Items' : 'Copy Items';
  if (confirmBtnText) confirmBtnText.innerText = action === 'move' ? 'Move Here' : 'Copy Here';
  if (summary) {
    summary.innerText = `Selected: ${paths.length} item${paths.length === 1 ? '' : 's'} to ${action}`;
  }

  if (confirmBtn) confirmBtn.disabled = false;
  if (cancelBtn) cancelBtn.disabled = false;
  if (progressContainer) {
    progressContainer.style.display = 'none';
    const fillEl = document.getElementById('copy-move-progress-fill');
    if (fillEl) fillEl.style.width = '0%';
  }

  // Populate drive select
  const driveSelect = document.getElementById('copy-move-drive-select');
  if (driveSelect) {
    driveSelect.innerHTML = '';
    state.roots.forEach(root => {
      const opt = document.createElement('option');
      opt.value = root.path;
      opt.innerText = root.name;
      driveSelect.appendChild(opt);
    });
  }

  syncCopyMoveDriveSelect(state.copyMoveTarget);
  loadCopyMoveDirectory(state.copyMoveTarget);

  if (modal) modal.classList.add('active');
  if (typeof lucide !== 'undefined') lucide.createIcons();
}
window.openCopyMoveModal = openCopyMoveModal;

function closeCopyMoveModal() {
  const modal = document.getElementById('modal-copy-move');
  if (modal) modal.classList.remove('active');
  const progressContainer = document.getElementById('copy-move-progress-container');
  if (progressContainer) progressContainer.style.display = 'none';
}
window.closeCopyMoveModal = closeCopyMoveModal;

function syncCopyMoveDriveSelect(path) {
  const driveSelect = document.getElementById('copy-move-drive-select');
  if (!driveSelect || !state.roots.length) return;
  const matchedRoot = findRootForPath(path);
  if (matchedRoot) {
    driveSelect.value = matchedRoot.path;
  }
}

async function loadCopyMoveDirectory(path) {
  state.copyMoveTarget = path;
  syncCopyMoveDriveSelect(path);

  const listContainer = document.getElementById('copy-move-list');
  const breadcrumbsContainer = document.getElementById('copy-move-breadcrumbs');
  const targetDisplay = document.getElementById('copy-move-current-target-display');

  if (targetDisplay) targetDisplay.innerText = `Target: ${path}`;
  if (listContainer) listContainer.innerHTML = '<div style="padding: 10px; color: var(--text-muted); text-align: center;">Loading folders...</div>';

  try {
    const data = await apiCall(`/api/files/browse?path=${encodeURIComponent(path)}`);
    if (!listContainer || !breadcrumbsContainer) return;
    listContainer.innerHTML = '';

    // Render breadcrumbs
    breadcrumbsContainer.innerHTML = '';
    const matchedRoot = findRootForPath(path);
    if (matchedRoot) {
      let relativePath = path.substring(matchedRoot.path.length);
      if (relativePath.startsWith('/')) relativePath = relativePath.substring(1);
      const segments = relativePath.split('/').filter(Boolean);

      const rootCrumb = document.createElement('span');
      rootCrumb.className = 'breadcrumb-item';
      rootCrumb.style.cssText = 'color: var(--primary); font-weight: 600; cursor: pointer;';
      rootCrumb.innerText = matchedRoot.name;
      rootCrumb.addEventListener('click', () => loadCopyMoveDirectory(matchedRoot.path));
      breadcrumbsContainer.appendChild(rootCrumb);

      let currentAccum = matchedRoot.path;
      segments.forEach((seg, idx) => {
        currentAccum += `/${seg}`;
        const finalPath = currentAccum;

        const sep = document.createElement('span');
        sep.style.cssText = 'color: var(--text-muted);';
        sep.innerText = '/';
        breadcrumbsContainer.appendChild(sep);

        const segCrumb = document.createElement('span');
        segCrumb.className = 'breadcrumb-item';
        segCrumb.style.cssText = idx === segments.length - 1 ? 'color: var(--text-primary); font-weight: 500;' : 'color: var(--text-secondary); cursor: pointer;';
        segCrumb.innerText = seg;
        if (idx !== segments.length - 1) {
          segCrumb.addEventListener('click', () => loadCopyMoveDirectory(finalPath));
        }
        breadcrumbsContainer.appendChild(segCrumb);
      });
    }

    // Up Directory navigation item
    const parentPath = getParentDirectory(path);
    if (parentPath && matchedRoot && path !== matchedRoot.path) {
      const upItem = document.createElement('div');
      upItem.className = 'copy-move-item';
      upItem.innerHTML = `
        <div style="display: flex; align-items: center; gap: 8px; color: var(--primary);">
          <i data-lucide="folder-up" style="width: 16px; height: 16px;"></i>
          <span>.. (Parent Directory)</span>
        </div>
      `;
      upItem.addEventListener('click', () => loadCopyMoveDirectory(parentPath));
      listContainer.appendChild(upItem);
    }

    // Show only subfolders for destination picking
    const folders = (data.files || []).filter(f => !f.isFile);
    if (folders.length === 0) {
      const emptyMsg = document.createElement('div');
      emptyMsg.style.cssText = 'padding: 15px; color: var(--text-muted); text-align: center; font-size: 13px;';
      emptyMsg.innerText = 'No subfolders in this directory. Current folder selected.';
      listContainer.appendChild(emptyMsg);
    } else {
      folders.forEach(folder => {
        const folderPath = path === '/' ? `/${folder.name}` : `${path}/${folder.name}`;
        
        // Circular check: cannot move/copy inside source folder
        const isCircular = state.copyMoveSources.some(src => folderPath === src || folderPath.startsWith(src + '/'));

        const itemEl = document.createElement('div');
        itemEl.className = 'copy-move-item';
        if (isCircular) {
          itemEl.style.opacity = '0.4';
          itemEl.style.cursor = 'not-allowed';
          itemEl.title = 'Cannot select a source folder or its subdirectories as destination';
        }

        itemEl.innerHTML = `
          <div style="display: flex; align-items: center; gap: 8px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
            <i data-lucide="folder" style="width: 16px; height: 16px; color: var(--primary); flex-shrink: 0;"></i>
            <span style="font-size: 13px;">${folder.name}</span>
          </div>
          <i data-lucide="chevron-right" style="width: 14px; height: 14px; color: var(--text-muted);"></i>
        `;

        if (!isCircular) {
          itemEl.addEventListener('click', () => {
            loadCopyMoveDirectory(folderPath);
          });
        }
        listContainer.appendChild(itemEl);
      });
    }

    if (typeof lucide !== 'undefined') lucide.createIcons();
  } catch (err) {
    if (listContainer) {
      listContainer.innerHTML = `<div style="padding: 10px; color: var(--error); text-align: center;">Error: ${err.message}</div>`;
    }
  }
}

async function confirmCopyMove() {
  if (!state.copyMoveSources || state.copyMoveSources.length === 0) return;
  const action = state.copyMoveAction;
  const destination = state.copyMoveTarget;
  const endpoint = action === 'move' ? '/api/files/move' : '/api/files/copy';
  const taskId = 'task_' + Date.now() + '_' + Math.random().toString(36).substring(2, 7);

  const confirmBtn = document.getElementById('btn-confirm-copy-move');
  const cancelBtn = document.getElementById('btn-cancel-copy-move');
  if (confirmBtn) {
    confirmBtn.disabled = true;
    confirmBtn.innerHTML = '<i data-lucide="loader" class="animate-spin" style="width: 14px; height: 14px;"></i> <span>Processing...</span>';
  }
  if (cancelBtn) cancelBtn.disabled = true;

  // Show progress immediately in modal
  updateFileOpProgressUI({
    taskId: taskId,
    action: action,
    currentFile: 'Preparing...',
    copiedBytes: 0,
    totalBytes: 0,
    copiedFiles: 0,
    totalFiles: state.copyMoveSources.length,
    percent: 0,
    speed: '',
    completed: false
  });

  try {
    const res = await apiCall(endpoint, {
      method: 'POST',
      body: JSON.stringify({
        sources: state.copyMoveSources,
        destination: destination,
        overwrite: false,
        taskId: taskId
      })
    });

    // Mark 100% complete
    updateFileOpProgressUI({
      taskId: taskId,
      action: action,
      currentFile: 'Finished!',
      percent: 100,
      completed: true
    });

    setTimeout(() => {
      closeCopyMoveModal();
      if (res.success) {
        showToast(`Successfully ${action === 'move' ? 'moved' : 'copied'} items!`, 'success');
        state.selectedPaths.clear();
        updateBatchActionBar();
        browsePath(state.currentPath);
      } else {
        showToast(`Completed with errors: ${(res.errors || []).join('; ')}`, 'error');
        state.selectedPaths.clear();
        updateBatchActionBar();
        browsePath(state.currentPath);
      }
    }, 400);
  } catch (err) {
    showToast(`Failed: ${err.message}`, 'error');
  } finally {
    if (confirmBtn) {
      confirmBtn.disabled = false;
      confirmBtn.innerHTML = `<i data-lucide="check"></i> <span>${action === 'move' ? 'Move Here' : 'Copy Here'}</span>`;
      if (typeof lucide !== 'undefined') lucide.createIcons();
    }
    if (cancelBtn) cancelBtn.disabled = false;
  }
}

function updateFileOpProgressUI(data) {
  const percent = Math.min(100, Math.max(0, data.percent || 0));
  const actionLabel = data.action === 'move' ? 'Moving' : 'Copying';
  const fileName = data.currentFile || 'Processing...';
  const speed = data.speed || '';
  const copiedBytes = formatBytes(data.copiedBytes || 0);
  const totalBytes = formatBytes(data.totalBytes || 0);
  const filesStr = `${data.copiedFiles || 0} / ${data.totalFiles || 1} files`;

  // Update in-modal progress card
  const modalProg = document.getElementById('copy-move-progress-container');
  if (modalProg) {
    modalProg.style.display = 'block';
    const titleEl = document.getElementById('copy-move-progress-title');
    const fileEl = document.getElementById('copy-move-progress-filename');
    const pctEl = document.getElementById('copy-move-progress-percentage');
    const speedEl = document.getElementById('copy-move-progress-speed');
    const fillEl = document.getElementById('copy-move-progress-fill');
    const filesEl = document.getElementById('copy-move-progress-files');
    const bytesEl = document.getElementById('copy-move-progress-bytes');

    if (titleEl) titleEl.innerText = `${actionLabel} files...`;
    if (fileEl) fileEl.innerText = fileName;
    if (pctEl) pctEl.innerText = `${percent}%`;
    if (speedEl) speedEl.innerText = speed;
    if (fillEl) fillEl.style.width = `${percent}%`;
    if (filesEl) filesEl.innerText = filesStr;
    if (bytesEl) bytesEl.innerText = `${copiedBytes} / ${totalBytes}`;
  }

  // Update sticky explorer header progress card
  const expProg = document.getElementById('file-op-progress-container');
  if (expProg) {
    expProg.style.display = 'block';
    const titleEl = document.getElementById('file-op-progress-title');
    const fileEl = document.getElementById('file-op-progress-filename');
    const pctEl = document.getElementById('file-op-progress-percentage');
    const speedEl = document.getElementById('file-op-progress-speed');
    const fillEl = document.getElementById('file-op-progress-fill');
    const filesEl = document.getElementById('file-op-progress-files');
    const bytesEl = document.getElementById('file-op-progress-bytes');

    if (titleEl) titleEl.innerText = `${actionLabel} files...`;
    if (fileEl) fileEl.innerText = fileName;
    if (pctEl) pctEl.innerText = `${percent}%`;
    if (speedEl) speedEl.innerText = speed;
    if (fillEl) fillEl.style.width = `${percent}%`;
    if (filesEl) filesEl.innerText = filesStr;
    if (bytesEl) bytesEl.innerText = `${copiedBytes} / ${totalBytes}`;

    if (data.completed) {
      setTimeout(() => {
        if (expProg) expProg.style.display = 'none';
      }, 2500);
    }
  }
}
window.updateFileOpProgressUI = updateFileOpProgressUI;

const UPLOAD_MAX_SINGLE_SIZE = 95 * 1024 * 1024; // 95MB to stay safely below Cloudflare's 100MB body limit
const UPLOAD_CHUNK_SIZE = 80 * 1024 * 1024; // 80MB chunks for larger files

async function uploadFileInChunks(file, basePath, relPath, onProgress) {
  if (state.isUploadCancelled) {
    throw new Error('Upload cancelled');
  }

  const startTime = Date.now();

  // If the file is small enough, upload it directly as a single request
  if (file.size <= UPLOAD_MAX_SINGLE_SIZE) {
    await new Promise((resolve, reject) => {
      if (state.isUploadCancelled) {
        reject(new Error('Upload cancelled'));
        return;
      }

      const formData = new FormData();
      formData.append('file', file);

      const xhr = new XMLHttpRequest();
      state.activeUploadXhr = xhr;

      const uploadUrl = `/api/files/upload?path=${encodeURIComponent(basePath)}&relativePath=${encodeURIComponent(relPath || '')}&filename=${encodeURIComponent(file.name)}`;
      
      xhr.open('POST', uploadUrl, true);
      xhr.setRequestHeader('Authorization', `Bearer ${state.token}`);

      xhr.upload.onprogress = (e) => {
        if (state.isUploadCancelled) return;
        if (e.lengthComputable && onProgress) {
          const loaded = e.loaded;
          const total = e.total;
          
          const elapsed = (Date.now() - startTime) / 1000;
          let speedString = '';
          if (elapsed > 0.1) {
            const bps = loaded / elapsed;
            if (bps > 1024 * 1024) {
              speedString = `${(bps / (1024 * 1024)).toFixed(1)} MB/s`;
            } else if (bps > 1024) {
              speedString = `${(bps / 1024).toFixed(1)} KB/s`;
            } else {
              speedString = `${bps.toFixed(0)} B/s`;
            }
          }
          onProgress(loaded / total, speedString);
        }
      };

      let settled = false;
      const handleDone = (errMessage) => {
        if (settled) return;
        settled = true;
        state.activeUploadXhr = null;
        if (state.isUploadCancelled || (errMessage && errMessage.includes('cancelled'))) {
          reject(new Error('Upload cancelled'));
        } else if (errMessage) {
          reject(new Error(errMessage));
        } else {
          resolve();
        }
      };

      xhr.onload = () => {
        if (state.isUploadCancelled) {
          handleDone('Upload cancelled');
          return;
        }
        if (xhr.status >= 200 && xhr.status < 300) {
          handleDone(null);
        } else {
          let errMsg = 'Failed to upload file';
          try {
            const res = JSON.parse(xhr.responseText);
            errMsg = res.error || errMsg;
          } catch (e) {}
          handleDone(errMsg);
        }
      };

      xhr.onerror = () => handleDone('Network error');
      xhr.onabort = () => handleDone('Upload cancelled');

      xhr.send(formData);
    });
  } else {
    // If the file is larger than 95MB, upload it in 80MB chunks to bypass Cloudflare request body size limits
    const uploadId = Math.random().toString(36).substring(2, 15) + Date.now().toString(36);
    const totalChunks = Math.ceil(file.size / UPLOAD_CHUNK_SIZE);

    for (let chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
      if (state.isUploadCancelled) {
        throw new Error('Upload cancelled');
      }

      const start = chunkIndex * UPLOAD_CHUNK_SIZE;
      const end = Math.min(start + UPLOAD_CHUNK_SIZE, file.size);
      const chunkBlob = file.slice(start, end);
      const chunkFile = new File([chunkBlob], file.name);

      await new Promise((resolve, reject) => {
        if (state.isUploadCancelled) {
          reject(new Error('Upload cancelled'));
          return;
        }

        const formData = new FormData();
        formData.append('file', chunkFile);

        const xhr = new XMLHttpRequest();
        state.activeUploadXhr = xhr;

        const uploadUrl = `/api/files/upload-chunk?uploadId=${uploadId}&chunkIndex=${chunkIndex}&totalChunks=${totalChunks}&path=${encodeURIComponent(basePath)}&relativePath=${encodeURIComponent(relPath || '')}&filename=${encodeURIComponent(file.name)}`;
        
        xhr.open('POST', uploadUrl, true);
        xhr.setRequestHeader('Authorization', `Bearer ${state.token}`);

        xhr.upload.onprogress = (e) => {
          if (state.isUploadCancelled) return;
          if (e.lengthComputable && onProgress) {
            const chunkLoaded = e.loaded;
            const chunkTotal = e.total;
            const totalLoaded = start + (chunkLoaded / chunkTotal) * (end - start);
            
            const elapsed = (Date.now() - startTime) / 1000;
            let speedString = '';
            if (elapsed > 0.1) {
              const bps = totalLoaded / elapsed;
              if (bps > 1024 * 1024) {
                speedString = `${(bps / (1024 * 1024)).toFixed(1)} MB/s`;
              } else if (bps > 1024) {
                speedString = `${(bps / 1024).toFixed(1)} KB/s`;
              } else {
                speedString = `${bps.toFixed(0)} B/s`;
              }
            }
            onProgress(totalLoaded / file.size, speedString);
          }
        };

        let settled = false;
        const handleDone = (errMessage) => {
          if (settled) return;
          settled = true;
          state.activeUploadXhr = null;
          if (state.isUploadCancelled || (errMessage && errMessage.includes('cancelled'))) {
            reject(new Error('Upload cancelled'));
          } else if (errMessage) {
            reject(new Error(errMessage));
          } else {
            resolve();
          }
        };

        xhr.onload = () => {
          if (state.isUploadCancelled) {
            handleDone('Upload cancelled');
            return;
          }
          if (xhr.status >= 200 && xhr.status < 300) {
            handleDone(null);
          } else {
            let errMsg = `Failed to upload chunk ${chunkIndex}`;
            try {
              const res = JSON.parse(xhr.responseText);
              errMsg = res.error || errMsg;
            } catch (e) {}
            handleDone(errMsg);
          }
        };

        xhr.onerror = () => handleDone('Network error');
        xhr.onabort = () => handleDone('Upload cancelled');

        xhr.send(formData);
      });
    }
  }
}

async function handleFolderUpload() {
  const folderInput = document.getElementById('folder-input');
  if (folderInput.files.length === 0) return;

  state.isUploadCancelled = false;
  state.activeUploadXhr = null;

  const files = Array.from(folderInput.files);
  const totalFiles = files.length;

  const progressContainer = document.getElementById('upload-progress-container');
  const filenameEl = document.getElementById('upload-filename');
  const percentEl = document.getElementById('upload-percentage');
  const fillEl = document.getElementById('upload-progress-fill');
  const speedEl = document.getElementById('upload-speed');

  filenameEl.innerText = `Preparing folder upload... (0/${totalFiles} files)`;
  percentEl.innerText = '0%';
  fillEl.style.width = '0%';
  if (speedEl) speedEl.innerText = '';
  progressContainer.style.display = 'block';
  lucide.createIcons();

  for (let i = 0; i < totalFiles; i++) {
    if (state.isUploadCancelled) break;
    const file = files[i];
    const relPath = file.webkitRelativePath || file.name;
    
    try {
      await uploadFileInChunks(file, state.currentPath, relPath, (fileRatio, speedString) => {
        if (state.isUploadCancelled) return;
        const overallPercent = Math.round(((i + fileRatio) / totalFiles) * 100);
        filenameEl.innerText = `Uploading: ${relPath} (${i + 1}/${totalFiles})`;
        percentEl.innerText = `${overallPercent}%`;
        fillEl.style.width = `${overallPercent}%`;
        if (speedEl) speedEl.innerText = speedString || '';
      });
    } catch (err) {
      if (state.isUploadCancelled || !err || err.message === 'Upload cancelled' || (err.message && err.message.includes('cancelled'))) {
        console.log('Folder upload cancelled by user');
        break;
      }
      console.error('File upload error in folder:', err);
    }
  }

  if (speedEl) speedEl.innerText = '';
  progressContainer.style.display = 'none';
  folderInput.value = ''; // Reset picker input
  state.activeUploadXhr = null;
  state.isUploadCancelled = false;
  browsePath(state.currentPath);
}

async function handleFileUpload() {
  const fileInput = document.getElementById('file-input');
  if (fileInput.files.length === 0) return;

  state.isUploadCancelled = false;
  state.activeUploadXhr = null;

  const files = Array.from(fileInput.files);
  const totalFiles = files.length;

  const progressContainer = document.getElementById('upload-progress-container');
  const filenameEl = document.getElementById('upload-filename');
  const percentEl = document.getElementById('upload-percentage');
  const fillEl = document.getElementById('upload-progress-fill');
  const speedEl = document.getElementById('upload-speed');

  filenameEl.innerText = totalFiles === 1 ? files[0].name : `Preparing upload... (0/${totalFiles} files)`;
  percentEl.innerText = '0%';
  fillEl.style.width = '0%';
  if (speedEl) speedEl.innerText = '';
  progressContainer.style.display = 'block';
  lucide.createIcons();

  for (let i = 0; i < totalFiles; i++) {
    if (state.isUploadCancelled) break;
    const file = files[i];
    try {
      await uploadFileInChunks(file, state.currentPath, '', (fileRatio, speedString) => {
        if (state.isUploadCancelled) return;
        const overallPercent = Math.round(((i + fileRatio) / totalFiles) * 100);
        filenameEl.innerText = totalFiles === 1 ? file.name : `Uploading: ${file.name} (${i + 1}/${totalFiles})`;
        percentEl.innerText = `${overallPercent}%`;
        fillEl.style.width = `${overallPercent}%`;
        if (speedEl) speedEl.innerText = speedString || '';
      });
    } catch (err) {
      if (state.isUploadCancelled || !err || err.message === 'Upload cancelled' || (err.message && err.message.includes('cancelled'))) {
        console.log('File upload cancelled by user');
        break;
      }
      console.error('File upload error:', err);
      alert(`Upload failed for ${file.name}: ${err.message}`);
    }
  }

  if (speedEl) speedEl.innerText = '';
  progressContainer.style.display = 'none';
  fileInput.value = '';
  state.activeUploadXhr = null;
  state.isUploadCancelled = false;
  browsePath(state.currentPath);
}

async function loadStorageAnalysis() {
  const containers = document.querySelectorAll('.storage-analysis-target');
  if (containers.length === 0) return;

  try {
    const stats = await apiCall('/api/admin/storage-analysis?_cb=' + Date.now());
    renderStorageAnalysis(stats);
  } catch (err) {
    console.error('Failed to load storage analysis:', err);
    containers.forEach(container => {
      container.innerHTML = `<div class="storage-loading text-error">Failed to load storage statistics: ${err.message}</div>`;
    });
  }
}

function renderStorageAnalysis(stats) {
  const containers = document.querySelectorAll('.storage-analysis-target');
  if (containers.length === 0) return;

  if (stats && stats.loading) {
    containers.forEach(container => {
      container.innerHTML = `
        <div class="storage-loading">
          <i data-lucide="loader" class="animate-spin"></i>
          <span>Loading storage statistics...</span>
        </div>
      `;
    });
    if (typeof lucide !== 'undefined') lucide.createIcons();
    return;
  }

  const createCard = (data) => {
    if (!data) return '';
    
    const formatGB = (bytes) => {
      const gb = bytes / (1024 * 1024 * 1024);
      if (gb >= 1000) {
        return `${(gb / 1024).toFixed(2)} TB`;
      }
      return `${gb.toFixed(1)} GB`;
    };

    const percent = parseFloat(data.usePercent.replace('%', ''));

    return `
      <div class="storage-card">
        <div class="storage-card-header">
          <div class="storage-card-title">
            <i data-lucide="hard-drive"></i>
            <span>${data.name}</span>
          </div>
          <div class="storage-usage-percent">${data.usePercent}</div>
        </div>
        <div class="storage-progress-container">
          <div class="storage-progress-bar">
            <div class="storage-progress-fill" style="width: ${percent}%"></div>
          </div>
        </div>
        <div class="storage-card-details">
          <div class="storage-detail-item">
            <span class="storage-detail-label">Used</span>
            <span class="storage-detail-value">${formatGB(data.used)}</span>
          </div>
          <div class="storage-detail-item">
            <span class="storage-detail-label">Available</span>
            <span class="storage-detail-value">${formatGB(data.available)}</span>
          </div>
          <div class="storage-detail-item" style="grid-column: span 2;">
            <span class="storage-detail-label">Total Capacity</span>
            <span class="storage-detail-value">${formatGB(data.total)}</span>
          </div>
        </div>
      </div>
    `;
  };

  const homeHtml = createCard(stats.home);
  const storageHtml = createCard(stats.storage);
  const hddHtml = createCard(stats.hdd);
  const gdriveHtml = createCard(stats.gdrive);
  const html = homeHtml + storageHtml + hddHtml + gdriveHtml;

  containers.forEach(container => {
    container.innerHTML = html;
  });
  lucide.createIcons();
}

// USER MANAGEMENT (ADMIN ONLY)
async function loadUsers() {
  try {
    const users = await apiCall('/api/users');
    state.users = users;
    renderUsers();
  } catch (err) {
    console.error('Failed to load users:', err);
  }
}

function renderUsers() {
  const tbody = document.getElementById('users-table-body');
  tbody.innerHTML = '';

  state.users.forEach(u => {
    const tr = document.createElement('tr');
    
    // Check path permissions count
    const permCount = u.permissions ? u.permissions.length : 0;
    
    // Only show Delete button if it is not the main owner account (id 1)
    const canDelete = u.id !== 1;
    const deleteBtn = canDelete 
      ? `<button class="btn btn-secondary text-error" onclick="handleDeleteUser(${u.id})">
           <i data-lucide="user-minus" style="width:16px; height:16px;"></i>
           <span>Delete</span>
         </button>` 
      : '';
      
    const escapedUsername = escapeJsStr(u.username);
    const configurePermsBtn = u.role !== 'admin'
      ? `<button class="btn btn-primary" onclick="openPermissionsModal(${u.id}, '${escapedUsername}')">
           <i data-lucide="key" style="width:16px; height:16px;"></i>
           <span>Permissions</span>
         </button>`
      : '<span class="text-muted" style="font-size: 13px;">Administrator</span>';

    const editBtn = `<button class="btn btn-secondary" onclick="openEditUserModal(${u.id}, '${escapedUsername}', '${u.role}', ${u.downloadBandwidthLimit || 0}, ${u.uploadBandwidthLimit || 0})">
                       <i data-lucide="edit" style="width:16px; height:16px;"></i>
                       <span>Edit</span>
                     </button>`;

    tr.innerHTML = `
      <td>
        <div style="display:flex; align-items:center; gap:10px;">
          <div style="width: 28px; height: 28px; border-radius: 50%; overflow: hidden; background: var(--bg-surface); border: 1px solid var(--border-subtle); display: flex; align-items: center; justify-content: center;">
            <img src="/api/users/avatar/${u.username}?v=${Date.now()}" alt="" style="width: 100%; height: 100%; object-fit: cover;">
          </div>
          <span style="font-weight: 500;">${escapeHtml(u.username)}</span>
        </div>
      </td>
      <td><span class="role-badge ${u.role}">${u.role}</span></td>
      <td>${u.role === 'admin' ? 'All (Full access)' : permCount + ' path rule(s)'}</td>
      <td>${formatBandwidth(u.downloadBandwidthLimit)}</td>
      <td>${formatBandwidth(u.uploadBandwidthLimit)}</td>
      <td>
        <div style="display:flex; gap:8px; align-items: center;">
          ${configurePermsBtn}
          ${editBtn}
          ${deleteBtn}
        </div>
      </td>
    `;
    tbody.appendChild(tr);
  });
  lucide.createIcons();
}

async function handleAddUser(e) {
  e.preventDefault();
  const usernameEl = document.getElementById('new-username');
  const passwordEl = document.getElementById('new-password');
  const roleEl = document.getElementById('new-role');
  const downloadBandwidthEl = document.getElementById('new-download-bandwidth');
  const uploadBandwidthEl = document.getElementById('new-upload-bandwidth');
  const errorEl = document.getElementById('add-user-error');
  
  errorEl.innerText = '';

  try {
    await apiCall('/api/users', {
      method: 'POST',
      body: JSON.stringify({
        username: usernameEl.value.trim(),
        password: passwordEl.value,
        role: roleEl.value,
        downloadBandwidthLimit: downloadBandwidthEl.value ? parseFloat(downloadBandwidthEl.value) : 0,
        uploadBandwidthLimit: uploadBandwidthEl.value ? parseFloat(uploadBandwidthEl.value) : 0
      })
    });

    closeModal('modal-add-user');
    
    usernameEl.value = '';
    passwordEl.value = '';
    roleEl.value = 'user';
    downloadBandwidthEl.value = '';
    uploadBandwidthEl.value = '';
    
    loadUsers();
  } catch (err) {
    errorEl.innerText = err.message || 'Failed to create user';
  }
}

async function handleDeleteUser(userId) {
  if (!confirm('Are you sure you want to delete this user? All their custom permissions will be removed.')) return;
  
  try {
    await apiCall(`/api/users/${userId}`, {
      method: 'DELETE'
    });
    loadUsers();
  } catch (err) {
    alert(`Failed to delete user: ${err.message}`);
  }
}

// PERMISSIONS CONFIGURATION UI
function openPermissionsModal(userId, username) {
  state.activePermissionsUserId = userId;
  document.getElementById('perm-username-title').innerText = username;
  
  // Find current permissions for user
  const user = state.users.find(u => u.id === userId);
  state.activePermissionsList = user && user.permissions 
    ? JSON.parse(JSON.stringify(user.permissions)) // deep clone
    : [];

  const writeEl = document.getElementById('rule-write');
  if (writeEl) writeEl.checked = true;

  renderPermissionRules();
  openModal('modal-permissions');
}

function renderPermissionRules() {
  const tbody = document.getElementById('rules-table-body');
  tbody.innerHTML = '';

  if (state.activePermissionsList.length === 0) {
    tbody.innerHTML = `<tr><td colspan="4" class="text-muted" style="text-align:center;">No access rules defined. User has zero access.</td></tr>`;
    return;
  }

  state.activePermissionsList.forEach((rule, idx) => {
    const tr = document.createElement('tr');
    const hasWrite = rule.allowWrite !== undefined ? rule.allowWrite : (rule.write !== undefined ? rule.write : false);
    tr.innerHTML = `
      <td><code>${rule.path}</code></td>
      <td><span class="badge-yes">Yes</span></td>
      <td><span class="${hasWrite ? 'badge-yes' : 'badge-no'}">${hasWrite ? 'Yes' : 'No'}</span></td>
      <td>
        <button class="btn btn-secondary" onclick="handleRemovePermissionRule(${idx})" style="padding: 4px 8px;">
          <i data-lucide="trash-2" style="width:14px; height:14px;"></i>
        </button>
      </td>
    `;
    tbody.appendChild(tr);
  });
  lucide.createIcons();
}

function handleAddPermissionRule() {
  const pathEl = document.getElementById('rule-path');
  const writeEl = document.getElementById('rule-write');
  
  const pathVal = pathEl.value.trim();
  if (!pathVal) return;

  const paths = pathVal.split(',').map(p => p.trim()).filter(p => p.length > 0);
  let duplicateCount = 0;

  paths.forEach(p => {
    if (state.activePermissionsList.some(r => r.path === p)) {
      duplicateCount++;
      return;
    }
    state.activePermissionsList.push({
      path: p,
      allowRead: true,
      allowWrite: writeEl.checked
    });
  });

  if (duplicateCount > 0 && duplicateCount === paths.length) {
    alert('Access rules for these paths already exist.');
  }

  // reset inputs
  pathEl.value = '';
  writeEl.checked = true;

  renderPermissionRules();
}

function handleRemovePermissionRule(index) {
  state.activePermissionsList.splice(index, 1);
  renderPermissionRules();
}

async function handleSavePermissions() {
  if (!state.activePermissionsUserId) return;

  const permissionsToSend = state.activePermissionsList.map(r => ({
    path: r.path,
    allowRead: r.allowRead !== undefined ? r.allowRead : (r.read !== undefined ? r.read : true),
    allowWrite: r.allowWrite !== undefined ? r.allowWrite : (r.write !== undefined ? r.write : false)
  }));

  try {
    await apiCall(`/api/users/${state.activePermissionsUserId}/permissions`, {
      method: 'PUT',
      body: JSON.stringify({
        permissions: permissionsToSend
      })
    });
    
    closeModal('modal-permissions');
    loadUsers();
  } catch (err) {
    alert(`Failed to save permissions: ${err.message}`);
  }
}

// MODAL CONTROLLERS
function openModal(modalId) {
  document.getElementById(modalId).classList.add('active');
}

function closeModal(modalId) {
  document.getElementById(modalId).classList.remove('active');
}

// UTILITY FUNCTIONS
function formatBytes(bytes, decimals = 2) {
  if (bytes === 0) return '0 Bytes';
  const k = 1024;
  const dm = decimals < 0 ? 0 : decimals;
  const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(dm)) + ' ' + sizes[i];
}

// SERVER MANAGEMENT
async function loadServerMetrics() {
  const container = document.getElementById('system-metrics-grid');
  try {
    const data = await apiCall('/api/admin/server-metrics');
    
    // CPU Card
    const cpuHtml = `
      <div class="storage-card">
        <div class="storage-card-header">
          <i data-lucide="cpu" class="storage-icon"></i>
          <span class="storage-title">CPU Utilization</span>
        </div>
        <div class="storage-usage-num">${data.cpuUsage}%</div>
        <div class="storage-progress-bg">
          <div class="storage-progress-fill" style="width: ${data.cpuUsage}%; background: linear-gradient(90deg, #ec4899, #f43f5e);"></div>
        </div>
        <div class="storage-details-row">
          <span>Load Avg (1/5/15m):</span>
          <span>${data.loadAvg.map(l => l.toFixed(2)).join(' / ')}</span>
        </div>
      </div>
    `;

    // RAM Card
    const ramPercent = data.memory.percent;
    const totalGB = (data.memory.total / (1024 * 1024 * 1024)).toFixed(1);
    const usedGB = (data.memory.used / (1024 * 1024 * 1024)).toFixed(1);
    const freeGB = (data.memory.free / (1024 * 1024 * 1024)).toFixed(1);
    const ramHtml = `
      <div class="storage-card">
        <div class="storage-card-header">
          <i data-lucide="database" class="storage-icon"></i>
          <span class="storage-title">System Memory (RAM)</span>
        </div>
        <div class="storage-usage-num">${ramPercent}%</div>
        <div class="storage-progress-bg">
          <div class="storage-progress-fill" style="width: ${ramPercent}%; background: linear-gradient(90deg, #a855f7, #6366f1);"></div>
        </div>
        <div class="storage-details-row">
          <span>Used: ${usedGB} GB / Total: ${totalGB} GB</span>
          <span>Free: ${freeGB} GB</span>
        </div>
      </div>
    `;

    // Uptime & Status Card
    const hostDays = Math.floor(data.uptime.host / 86400);
    const hostHours = Math.floor((data.uptime.host % 86400) / 3600);
    const hostMins = Math.floor((data.uptime.host % 3600) / 60);
    const hostUptimeStr = `${hostDays}d ${hostHours}h ${hostMins}m`;

    const procHours = Math.floor(data.uptime.process / 3600);
    const procMins = Math.floor((data.uptime.process % 3600) / 60);
    const procSecs = Math.floor(data.uptime.process % 60);
    const procUptimeStr = `${procHours}h ${procMins}m ${procSecs}s`;

    const statusHtml = `
      <div class="storage-card">
        <div class="storage-card-header">
          <i data-lucide="activity" class="storage-icon"></i>
          <span class="storage-title">Services & Status</span>
        </div>
        <div style="margin-top: 15px; display: flex; flex-direction: column; gap: 10px;">
          <div style="display: flex; justify-content: space-between; align-items: center; font-size: 13px;">
            <span>Media Server Service:</span>
            <span class="badge ${data.services.mediaServer === 'active' ? 'badge-active' : 'badge-inactive'}">${data.services.mediaServer}</span>
          </div>
          <div style="display: flex; justify-content: space-between; align-items: center; font-size: 13px;">
            <span>Cloudflare Tunnel:</span>
            <span class="badge ${data.services.cloudflared === 'active' ? 'badge-active' : 'badge-inactive'}">${data.services.cloudflared}</span>
          </div>
          <div style="margin-top: 5px; font-size: 11px; color: var(--text-secondary); display: flex; flex-direction: column; gap: 4px; border-top: 1px solid var(--border-subtle); padding-top: 8px;">
            <div>Host Uptime: ${hostUptimeStr}</div>
            <div>App Uptime: ${procUptimeStr}</div>
            <div>Node version: ${data.os.nodeVersion} (${data.os.platform} ${data.os.arch})</div>
          </div>
        </div>
      </div>
    `;

    container.innerHTML = cpuHtml + ramHtml + statusHtml;
    lucide.createIcons();
  } catch (err) {
    console.error('Failed to load server metrics:', err);
    container.innerHTML = `<div class="error-message">Failed to load server metrics: ${err.message}</div>`;
  }
}

async function loadServerProcesses() {
  const tbody = document.getElementById('processes-table-body');
  try {
    const processes = await apiCall('/api/admin/server-processes');
    tbody.innerHTML = '';

    if (processes.length === 0) {
      tbody.innerHTML = `<tr><td colspan="6" style="text-align: center; color: var(--text-secondary); padding: 24px;">No active ffmpeg or ffprobe processes found</td></tr>`;
      return;
    }

    processes.forEach(proc => {
      const row = document.createElement('tr');
      row.innerHTML = `
        <td style="font-family: monospace; font-weight: bold; color: var(--text-primary);">${proc.pid}</td>
        <td>${proc.cpu}%</td>
        <td>${proc.mem}%</td>
        <td>${proc.etime}</td>
        <td style="max-width: 400px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: monospace; font-size: 11px;" title="${proc.command}">${proc.command}</td>
        <td>
          <button class="btn btn-danger btn-sm" onclick="runServerAction('kill-process', ${proc.pid})">
            <i data-lucide="trash-2" style="width: 14px; height: 14px;"></i>
            <span>Kill</span>
          </button>
        </td>
      `;
      tbody.appendChild(row);
    });
    lucide.createIcons();
  } catch (err) {
    console.error('Failed to load active processes:', err);
    tbody.innerHTML = `<tr><td colspan="6" class="error-message">Failed to load processes: ${err.message}</td></tr>`;
  }
}

async function loadServerLogs() {
  const consoleOutput = document.getElementById('log-console-output');
  try {
    const data = await apiCall('/api/admin/server-logs');
    consoleOutput.innerText = data.logs || 'No log lines found.';
    const container = consoleOutput.parentElement;
    container.scrollTop = container.scrollHeight;
  } catch (err) {
    console.error('Failed to load server logs:', err);
    consoleOutput.innerText = `Failed to load logs: ${err.message}`;
  }
}

async function runServerAction(action, pid = null) {
  let confirmMessage = '';
  if (action === 'restart-service') {
    confirmMessage = 'Are you sure you want to restart the application service? The dashboard will disconnect temporarily.';
  } else if (action === 'reboot-host') {
    confirmMessage = 'WARNING: Are you sure you want to reboot the entire host server machine? This will terminate all active streams and connection will be lost for a few minutes.';
  } else if (action === 'kill-process') {
    confirmMessage = `Are you sure you want to terminate process ID ${pid}?`;
  }

  if (confirmMessage && !confirm(confirmMessage)) {
    return;
  }

  try {
    const res = await apiCall('/api/admin/server-action', {
      method: 'POST',
      body: JSON.stringify({ action, pid })
    });

    alert(res.message || 'Action completed successfully');

    if (action === 'kill-process') {
      loadServerProcesses();
    } else if (action === 'restart-service') {
      setTimeout(() => {
        window.location.reload();
      }, 3000);
    }
  } catch (err) {
    alert(`Failed to complete action: ${err.message}`);
  }
}

// Expose runServerAction to global scope for HTML inline calls
window.runServerAction = runServerAction;

// -------------------------------------------------------------
// SUB-TAB NAVIGATION ENGINE
// -------------------------------------------------------------
function switchSubTab(tabId) {
  document.querySelectorAll('.server-tab-btn').forEach(btn => {
    if (btn.getAttribute('data-tab') === tabId) {
      btn.classList.add('active');
    } else {
      btn.classList.remove('active');
    }
  });

  document.querySelectorAll('.server-tab-content').forEach(panel => {
    if (panel.id === tabId) {
      panel.classList.add('active');
    } else {
      panel.classList.remove('active');
    }
  });

  // Fetch data immediately for the target tab
  triggerSubTabLoad(tabId);
}

function triggerSubTabLoad(tabId) {
  if (tabId === 'tab-metrics') {
    loadServerMetrics();
  } else if (tabId === 'tab-docker') {
    loadDockerContainers();
  } else if (tabId === 'tab-services') {
    loadsystemdServices();
  } else if (tabId === 'tab-processes') {
    loadServerProcesses();
  } else if (tabId === 'tab-network') {
    loadVpnAndInterfaces();
  } else if (tabId === 'tab-storage') {
    loadStorageAnalysis();
  } else if (tabId === 'tab-firewall') {
    loadFirewallRules();
  } else if (tabId === 'tab-cron') {
    loadCronJobs();
  } else if (tabId === 'tab-packages') {
    loadAptPackages('');
  } else if (tabId === 'tab-system-logs') {
    loadServerLogs();
  } else if (tabId === 'tab-audit-logs') {
    loadAuditLogs();
  } else if (tabId === 'tab-appearance') {
    applyTheme();
    applyUiStyle();
  }
}

function refreshActiveSubTab(tabId) {
  if (tabId === 'tab-metrics') {
    loadServerMetrics();
  } else if (tabId === 'tab-docker') {
    loadDockerContainers();
  } else if (tabId === 'tab-processes') {
    loadServerProcesses();
  }
}

window.switchSubTab = switchSubTab;

// -------------------------------------------------------------
// TELEMETRY & CHART.JS ENGINE
// -------------------------------------------------------------
let cpuDataHistory = [];
let ramDataHistory = [];
let chartLabels = [];

function updateTelemetryCharts(cpuUsage, ramUsage) {
  if (typeof Chart === 'undefined') {
    console.warn("Chart.js is not loaded or ready yet");
    return;
  }
  const timeStr = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  
  if (chartLabels.length >= 15) {
    chartLabels.shift();
    cpuDataHistory.shift();
    ramDataHistory.shift();
  }
  
  chartLabels.push(timeStr);
  cpuDataHistory.push(cpuUsage);
  ramDataHistory.push(ramUsage);

  // Initialize CPU Chart
  if (!state.cpuChart) {
    const ctxCpu = document.getElementById('chart-cpu').getContext('2d');
    state.cpuChart = new Chart(ctxCpu, {
      type: 'line',
      data: {
        labels: chartLabels,
        datasets: [{
          label: 'CPU Load %',
          data: cpuDataHistory,
          borderColor: '#00adb5',
          backgroundColor: 'rgba(0, 173, 181, 0.1)',
          fill: true,
          tension: 0.3,
          borderWidth: 2
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        scales: {
          y: { min: 0, max: 100, grid: { color: 'rgba(255, 255, 255, 0.05)' } },
          x: { grid: { display: false } }
        },
        plugins: { legend: { display: false } }
      }
    });
  } else {
    state.cpuChart.update('none');
  }

  // Initialize RAM Chart
  if (!state.ramChart) {
    const ctxRam = document.getElementById('chart-ram').getContext('2d');
    state.ramChart = new Chart(ctxRam, {
      type: 'line',
      data: {
        labels: chartLabels,
        datasets: [{
          label: 'Memory Load %',
          data: ramDataHistory,
          borderColor: '#2a6fdb',
          backgroundColor: 'rgba(42, 111, 219, 0.1)',
          fill: true,
          tension: 0.3,
          borderWidth: 2
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        scales: {
          y: { min: 0, max: 100, grid: { color: 'rgba(255, 255, 255, 0.05)' } },
          x: { grid: { display: false } }
        },
        plugins: { legend: { display: false } }
      }
    });
  } else {
    state.ramChart.update('none');
  }
}

// Override original loadServerMetrics to integrate Chart.js updates
async function loadServerMetrics() {
  const container = document.getElementById('system-metrics-grid');
  try {
    const data = await apiCall('/api/admin/server-metrics');
    
    // CPU Card
    const cpuHtml = `
      <div class="storage-card">
        <div class="storage-card-header">
          <i data-lucide="cpu" class="storage-icon"></i>
          <span class="storage-title">CPU Utilization</span>
        </div>
        <div class="storage-usage-num">${data.cpuUsage}%</div>
        <div class="storage-progress-bg">
          <div class="storage-progress-fill" style="width: ${data.cpuUsage}%; background: linear-gradient(90deg, #ec4899, #f43f5e);"></div>
        </div>
        <div class="storage-details-row">
          <span>Load Avg (1/5/15m):</span>
          <span>${data.loadAvg.map(l => l.toFixed(2)).join(' / ')}</span>
        </div>
      </div>
    `;

    // RAM Card
    const ramPercent = data.memory.percent;
    const totalGB = (data.memory.total / (1024 * 1024 * 1024)).toFixed(1);
    const usedGB = (data.memory.used / (1024 * 1024 * 1024)).toFixed(1);
    const freeGB = (data.memory.free / (1024 * 1024 * 1024)).toFixed(1);
    const ramHtml = `
      <div class="storage-card">
        <div class="storage-card-header">
          <i data-lucide="database" class="storage-icon"></i>
          <span class="storage-title">System Memory (RAM)</span>
        </div>
        <div class="storage-usage-num">${ramPercent}%</div>
        <div class="storage-progress-bg">
          <div class="storage-progress-fill" style="width: ${ramPercent}%; background: linear-gradient(90deg, #a855f7, #6366f1);"></div>
        </div>
        <div class="storage-details-row">
          <span>Used: ${usedGB} GB / Total: ${totalGB} GB</span>
          <span>Free: ${freeGB} GB</span>
        </div>
      </div>
    `;

    // Uptime & Status Card
    const hostDays = Math.floor(data.uptime.host / 86400);
    const hostHours = Math.floor((data.uptime.host % 86400) / 3600);
    const hostMins = Math.floor((data.uptime.host % 3600) / 60);
    const hostUptimeStr = `${hostDays}d ${hostHours}h ${hostMins}m`;

    const procHours = Math.floor(data.uptime.process / 3600);
    const procMins = Math.floor((data.uptime.process % 3600) / 60);
    const procSecs = Math.floor(data.uptime.process % 60);
    const procUptimeStr = `${procHours}h ${procMins}m ${procSecs}s`;

    const statusHtml = `
      <div class="storage-card">
        <div class="storage-card-header">
          <i data-lucide="activity" class="storage-icon"></i>
          <span class="storage-title">Services & Status</span>
        </div>
        <div style="margin-top: 15px; display: flex; flex-direction: column; gap: 10px;">
          <div style="display: flex; justify-content: space-between; align-items: center; font-size: 13px;">
            <span>Media Server Service:</span>
            <span class="badge ${data.services.mediaServer === 'active' ? 'badge-active' : 'badge-inactive'}">${data.services.mediaServer}</span>
          </div>
          <div style="display: flex; justify-content: space-between; align-items: center; font-size: 13px;">
            <span>Cloudflare Tunnel:</span>
            <span class="badge ${data.services.cloudflared === 'active' ? 'badge-active' : 'badge-inactive'}">${data.services.cloudflared}</span>
          </div>
          <div style="margin-top: 5px; font-size: 11px; color: var(--text-secondary); display: flex; flex-direction: column; gap: 4px; border-top: 1px solid var(--border-subtle); padding-top: 8px;">
            <div>Host Uptime: ${hostUptimeStr}</div>
            <div>App Uptime: ${procUptimeStr}</div>
            <div>Node version: ${data.os.nodeVersion} (${data.os.platform} ${data.os.arch})</div>
          </div>
        </div>
      </div>
    `;

    container.innerHTML = cpuHtml + ramHtml + statusHtml;
    lucide.createIcons();

    // Trigger Chart.js histories push
    updateTelemetryCharts(data.cpuUsage, ramPercent);
  } catch (err) {
    console.error('Failed to load server metrics:', err);
    container.innerHTML = `<div class="error-message">Failed to load server metrics: ${err.message}</div>`;
  }
}

// -------------------------------------------------------------
// DOCKER CONTAINERS ACTIONS
// -------------------------------------------------------------
async function loadDockerContainers() {
  const tbody = document.getElementById('docker-table-body');
  try {
    const res = await apiCall('/api/admin/docker/containers');
    tbody.innerHTML = '';
    
    if (!res.dockerActive) {
      tbody.innerHTML = `<tr><td colspan="7" style="text-align: center; color: var(--text-secondary); padding: 20px;">Docker daemon is not running on the host</td></tr>`;
      return;
    }
    if (res.containers.length === 0) {
      tbody.innerHTML = `<tr><td colspan="7" style="text-align: center; color: var(--text-secondary); padding: 20px;">No containers found</td></tr>`;
      return;
    }

    res.containers.forEach(c => {
      const row = document.createElement('tr');
      row.innerHTML = `
        <td style="font-weight: bold; color: var(--text-primary);">${c.name}</td>
        <td style="font-family: monospace; font-size: 11px;">${c.image}</td>
        <td style="font-family: monospace; font-size: 11px;">${c.ports || '--'}</td>
        <td style="font-family: monospace;">${c.cpu || '0.0%'}</td>
        <td style="font-family: monospace;">${c.memory || '0B'}</td>
        <td>
          <span class="badge ${c.state === 'running' ? 'badge-active' : 'badge-inactive'}">${c.state}</span>
        </td>
        <td>
          <div style="display: flex; gap: 6px;">
            ${c.state === 'running' ? 
              `<button class="btn btn-secondary btn-sm" onclick="runDockerAction('${c.id}', 'stop')">Stop</button>` : 
              `<button class="btn btn-primary btn-sm" onclick="runDockerAction('${c.id}', 'start')">Start</button>`
            }
            <button class="btn btn-secondary btn-sm" onclick="runDockerAction('${c.id}', 'restart')">Restart</button>
            <button class="btn btn-danger btn-sm" onclick="runDockerAction('${c.id}', 'rm')">Remove</button>
          </div>
        </td>
      `;
      tbody.appendChild(row);
    });
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="7" class="error-message">Failed to load containers: ${err.message}</td></tr>`;
  }
}

async function runDockerAction(id, action) {
  if (!confirm(`Are you sure you want to ${action} container ${id}?`)) return;
  try {
    await apiCall('/api/admin/docker/containers/control', {
      method: 'POST',
      body: JSON.stringify({ id, action })
    });
    loadDockerContainers();
  } catch (err) {
    alert(`Docker action failed: ${err.message}`);
  }
}

window.runDockerAction = runDockerAction;

// -------------------------------------------------------------
// SYSTEMD CORE SERVICES
// -------------------------------------------------------------
async function loadsystemdServices() {
  const tbody = document.getElementById('services-table-body');
  try {
    const data = await apiCall('/api/admin/server-metrics');
    tbody.innerHTML = '';

    const list = [
      { name: 'media-server.service', load: 'loaded', active: data.services.mediaServer, sub: 'running', desc: 'Sakura Media Server Daemon' },
      { name: 'cloudflared.service', load: 'loaded', active: data.services.cloudflared, sub: 'running', desc: 'Cloudflare Tunnels Gateway client' }
    ];

    list.forEach(s => {
      const row = document.createElement('tr');
      row.innerHTML = `
        <td style="font-family: monospace; font-weight: bold; color: var(--text-primary);">${s.name}</td>
        <td>${s.load}</td>
        <td>
          <span class="badge ${s.active === 'active' ? 'badge-active' : 'badge-inactive'}">${s.active}</span>
        </td>
        <td>${s.sub}</td>
        <td>${s.desc}</td>
        <td>
          <button class="btn btn-secondary btn-sm" onclick="runServerAction('restart-service')">Restart</button>
        </td>
      `;
      tbody.appendChild(row);
    });
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="6" class="error-message">Failed to load services: ${err.message}</td></tr>`;
  }
}

// -------------------------------------------------------------
// FIREWALL RULES ACTIONS
// -------------------------------------------------------------
async function loadFirewallRules() {
  const tbody = document.getElementById('firewall-table-body');
  try {
    const res = await apiCall('/api/admin/config/firewall');
    tbody.innerHTML = '';
    
    if (res.rules.length === 0) {
      tbody.innerHTML = `<tr><td colspan="4" style="text-align: center; color: var(--text-secondary); padding: 20px;">No firewall rules registered in DB</td></tr>`;
      return;
    }

    res.rules.forEach(r => {
      const row = document.createElement('tr');
      row.innerHTML = `
        <td style="font-family: monospace; font-weight: bold; color: var(--text-primary);">${r.port}</td>
        <td style="font-family: monospace;">${r.protocol.toUpperCase()}</td>
        <td>
          <span class="badge ${r.action === 'allow' ? 'badge-active' : 'badge-inactive'}">${r.action}</span>
        </td>
        <td>
          <button class="btn btn-danger btn-sm" onclick="deleteFirewallRule(${r.id})">Delete</button>
        </td>
      `;
      tbody.appendChild(row);
    });
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="4" class="error-message">Failed to load firewall: ${err.message}</td></tr>`;
  }
}

async function handleAddFirewallRule(e) {
  e.preventDefault();
  const port = parseInt(document.getElementById('fw-port').value);
  const protocol = document.getElementById('fw-proto').value;
  const action = document.getElementById('fw-action').value;

  try {
    await apiCall('/api/admin/config/firewall', {
      method: 'POST',
      body: JSON.stringify({ port, protocol, action })
    });
    document.getElementById('fw-port').value = '';
    loadFirewallRules();
  } catch(err) {
    alert(`Failed to add firewall rule: ${err.message}`);
  }
}

async function deleteFirewallRule(id) {
  if (!confirm('Are you sure you want to delete this firewall rule?')) return;
  try {
    await apiCall(`/api/admin/config/firewall/${id}`, {
      method: 'DELETE'
    });
    loadFirewallRules();
  } catch (err) {
    alert(`Delete failed: ${err.message}`);
  }
}

window.deleteFirewallRule = deleteFirewallRule;

// -------------------------------------------------------------
// SCHEDULER ACTIONS
// -------------------------------------------------------------
async function loadCronJobs() {
  const tbody = document.getElementById('cron-table-body');
  try {
    const jobs = await apiCall('/api/admin/config/cron');
    tbody.innerHTML = '';
    
    if (jobs.length === 0) {
      tbody.innerHTML = `<tr><td colspan="5" style="text-align: center; color: var(--text-secondary); padding: 20px;">No scheduled tasks registered</td></tr>`;
      return;
    }

    jobs.forEach(j => {
      const row = document.createElement('tr');
      row.innerHTML = `
        <td style="font-weight: bold; color: var(--text-primary);">${j.name}</td>
        <td style="font-family: monospace;">${j.cronExpression}</td>
        <td style="font-family: monospace; font-size: 11px;">${j.command}</td>
        <td><span class="badge badge-active">${j.type}</span></td>
        <td>
          <button class="btn btn-danger btn-sm" onclick="deleteCronJob(${j.id})">Delete</button>
        </td>
      `;
      tbody.appendChild(row);
    });
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="5" class="error-message">Failed to load scheduler: ${err.message}</td></tr>`;
  }
}

async function handleAddCronJob(e) {
  e.preventDefault();
  const name = document.getElementById('cron-name').value;
  const cronExpression = document.getElementById('cron-expr').value;
  const command = document.getElementById('cron-cmd').value;
  const type = document.getElementById('cron-type').value;

  try {
    await apiCall('/api/admin/config/cron', {
      method: 'POST',
      body: JSON.stringify({ name, cronExpression, command, type })
    });
    document.getElementById('cron-name').value = '';
    document.getElementById('cron-expr').value = '';
    document.getElementById('cron-cmd').value = '';
    loadCronJobs();
  } catch(err) {
    alert(`Failed to save task: ${err.message}`);
  }
}

async function deleteCronJob(id) {
  if (!confirm('Are you sure you want to delete this scheduled task?')) return;
  try {
    await apiCall(`/api/admin/config/cron/${id}`, {
      method: 'DELETE'
    });
    loadCronJobs();
  } catch (err) {
    alert(`Delete failed: ${err.message}`);
  }
}

window.deleteCronJob = deleteCronJob;

// -------------------------------------------------------------
// PACKAGE MANAGER ACTIONS
// -------------------------------------------------------------
async function loadAptPackages(search = '') {
  const tbody = document.getElementById('packages-table-body');
  try {
    const url = search ? `/api/admin/system/packages?search=${encodeURIComponent(search)}` : '/api/admin/system/packages';
    const packages = await apiCall(url);
    tbody.innerHTML = '';
    
    if (packages.length === 0) {
      tbody.innerHTML = `<tr><td colspan="4" style="text-align: center; color: var(--text-secondary); padding: 20px;">No upgradable packages in registry</td></tr>`;
      return;
    }

    packages.forEach(p => {
      const row = document.createElement('tr');
      row.innerHTML = `
        <td style="font-family: monospace; font-weight: bold; color: var(--text-primary);">${p.name}</td>
        <td>${p.description}</td>
        <td><span class="badge ${p.status === 'upgradable' ? 'badge-inactive' : 'badge-active'}">${p.status}</span></td>
        <td>
          <button class="btn btn-primary btn-sm" onclick="runPackageAction('install', '${p.name}')">Install/Update</button>
        </td>
      `;
      tbody.appendChild(row);
    });
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="4" class="error-message">Failed to load packages: ${err.message}</td></tr>`;
  }
}

async function runPackageAction(action, pkgName) {
  try {
    await apiCall('/api/admin/system/packages/action', {
      method: 'POST',
      body: JSON.stringify({ action, pkgName })
    });
    alert(`Package operation ${action} started in background. Check system logs for output.`);
  } catch(err) {
    alert(`Operation failed: ${err.message}`);
  }
}

window.runPackageAction = runPackageAction;

// -------------------------------------------------------------
// NETWORKS & AUDIT LOGS LOADERS
// -------------------------------------------------------------
async function loadVpnAndInterfaces() {
  const netContainer = document.getElementById('net-interfaces-container');
  const vpnContainer = document.getElementById('vpn-status-container');
  
  try {
    const metrics = await apiCall('/api/admin/server-metrics');
    netContainer.innerHTML = `
      <div style="margin-bottom: 8px;"><strong>Hostname:</strong> ${metrics.os.platform}</div>
      <div style="margin-bottom: 8px;"><strong>Local IP:</strong> 192.168.0.10</div>
      <div style="margin-bottom: 8px;"><strong>Active Subnet:</strong> Gateway Connected</div>
    `;

    const vpn = await apiCall('/api/admin/config/vpn');
    vpnContainer.innerHTML = `
      <div style="margin-bottom: 8px;"><strong>WireGuard VPN:</strong> <span class="badge ${vpn.wireguard.active ? 'badge-active' : 'badge-inactive'}">${vpn.wireguard.active ? 'Connected' : 'Offline'}</span></div>
      <div style="margin-bottom: 8px;"><strong>Tailscale mesh:</strong> <span class="badge ${vpn.tailscale.active ? 'badge-active' : 'badge-inactive'}">${vpn.tailscale.active ? 'Active' : 'Offline'}</span></div>
    `;
  } catch(e) {}
}

async function loadAuditLogs() {
  const tbody = document.getElementById('audit-table-body');
  try {
    const logs = await apiCall('/api/admin/audit-logs');
    tbody.innerHTML = '';
    
    if (logs.length === 0) {
      tbody.innerHTML = `<tr><td colspan="4" style="text-align: center; color: var(--text-secondary); padding: 20px;">No audit events logged</td></tr>`;
      return;
    }

    logs.forEach(l => {
      const row = document.createElement('tr');
      row.innerHTML = `
        <td style="font-weight: bold; color: var(--text-primary);">${l.username}</td>
        <td>${l.action}</td>
        <td style="font-family: monospace;">${l.ipAddress || '--'}</td>
        <td style="color: var(--text-muted);">${new Date(l.timestamp).toLocaleString()}</td>
      `;
      tbody.appendChild(row);
    });
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="4" class="error-message">Failed to load audit trail: ${err.message}</td></tr>`;
  }
}

// -------------------------------------------------------------
// EDIT USER ACTIONS
// -------------------------------------------------------------
function openEditUserModal(userId, username, role, downloadBandwidthLimit, uploadBandwidthLimit) {
  document.getElementById('edit-user-id').value = userId;
  document.getElementById('edit-username-title').innerText = username;
  document.getElementById('edit-username-display').value = username;
  document.getElementById('edit-password').value = '';
  document.getElementById('edit-password').type = 'password';

  const avatarPreview = document.getElementById('edit-avatar-preview');
  if (avatarPreview) {
    avatarPreview.src = `/api/users/avatar/${username}?v=${Date.now()}`;
  }

  const user = state.users ? state.users.find(u => u.id === userId) : null;
  const plainPassword = (user && user.plainPassword) ? user.plainPassword : 'Unknown (Hashed)';
  document.getElementById('edit-current-password-display').innerText = plainPassword;

  document.getElementById('edit-role').value = role;
  
  const parsedDlLimit = parseFloat(downloadBandwidthLimit);
  document.getElementById('edit-download-bandwidth').value = (parsedDlLimit && parsedDlLimit > 0) ? parsedDlLimit : '';

  const parsedUlLimit = parseFloat(uploadBandwidthLimit);
  document.getElementById('edit-upload-bandwidth').value = (parsedUlLimit && parsedUlLimit > 0) ? parsedUlLimit : '';

  document.getElementById('edit-user-error').innerText = '';
  
  const icon = document.querySelector('#btn-toggle-edit-password i');
  if (icon) icon.setAttribute('data-lucide', 'eye');
  lucide.createIcons();

  const roleSelect = document.getElementById('edit-role');
  if (userId === 1) {
    roleSelect.disabled = true;
  } else {
    roleSelect.disabled = false;
  }

  openModal('modal-edit-user');
}

async function handleAdminAvatarUpload(e) {
  const userId = document.getElementById('edit-user-id').value;
  const username = document.getElementById('edit-username-title').innerText;
  const file = e.target.files[0];
  if (!file || !userId) return;

  const errorEl = document.getElementById('edit-user-error');
  if (errorEl) errorEl.innerText = '';

  if (file.size > 5 * 1024 * 1024) {
    if (errorEl) errorEl.innerText = 'File size exceeds 5MB limit';
    e.target.value = '';
    return;
  }

  const formData = new FormData();
  formData.append('file', file);

  try {
    const res = await apiCall(`/api/users/${userId}/avatar`, {
      method: 'POST',
      body: formData
    });

    const avatarPreview = document.getElementById('edit-avatar-preview');
    if (avatarPreview) {
      avatarPreview.src = `/api/users/avatar/${username}?v=${Date.now()}`;
    }
    
    await loadUsers();
    if (state.user && state.user.id == userId) {
      updateUserProfileUI();
    }
  } catch (err) {
    if (errorEl) errorEl.innerText = err.message || 'Failed to upload user avatar';
  } finally {
    e.target.value = '';
  }
}

async function handleAdminAvatarDelete() {
  const userId = document.getElementById('edit-user-id').value;
  const username = document.getElementById('edit-username-title').innerText;
  if (!userId) return;

  const errorEl = document.getElementById('edit-user-error');
  if (errorEl) errorEl.innerText = '';

  if (!confirm(`Are you sure you want to remove ${username}'s profile picture?`)) {
    return;
  }

  try {
    await apiCall(`/api/users/${userId}/avatar`, {
      method: 'DELETE'
    });

    const avatarPreview = document.getElementById('edit-avatar-preview');
    if (avatarPreview) {
      avatarPreview.src = `/api/users/avatar/${username}?v=${Date.now()}`;
    }

    await loadUsers();
    if (state.user && state.user.id == userId) {
      updateUserProfileUI();
    }
  } catch (err) {
    if (errorEl) errorEl.innerText = err.message || 'Failed to remove user avatar';
  }
}

async function handleEditUser(e) {
  e.preventDefault();
  const userId = document.getElementById('edit-user-id').value;
  const username = document.getElementById('edit-username-display').value;
  const password = document.getElementById('edit-password').value;
  const role = document.getElementById('edit-role').value;
  const downloadBandwidth = document.getElementById('edit-download-bandwidth').value;
  const uploadBandwidth = document.getElementById('edit-upload-bandwidth').value;
  const errorEl = document.getElementById('edit-user-error');

  errorEl.innerText = '';

  try {
    const payload = { 
      username,
      role,
      downloadBandwidthLimit: downloadBandwidth ? parseFloat(downloadBandwidth) : 0,
      uploadBandwidthLimit: uploadBandwidth ? parseFloat(uploadBandwidth) : 0
    };
    if (password.trim()) {
      payload.password = password;
    }

    await apiCall(`/api/users/${userId}`, {
      method: 'PUT',
      body: JSON.stringify(payload)
    });

    closeModal('modal-edit-user');
    loadUsers();
  } catch (err) {
    errorEl.innerText = err.message || 'Failed to update user';
  }
}

function toggleEditPasswordVisibility() {
  const pwdInput = document.getElementById('edit-password');
  const btn = document.getElementById('btn-toggle-edit-password');
  const icon = btn.querySelector('i');
  
  if (pwdInput.type === 'password') {
    pwdInput.type = 'text';
    icon.setAttribute('data-lucide', 'eye-off');
  } else {
    pwdInput.type = 'password';
    icon.setAttribute('data-lucide', 'eye');
  }
  lucide.createIcons();
}

window.openEditUserModal = openEditUserModal;
window.handleEditUser = handleEditUser;
window.toggleEditPasswordVisibility = toggleEditPasswordVisibility;

function formatTime(seconds) {
  if (isNaN(seconds)) return '0:00';
  const hrs = Math.floor(seconds / 3600);
  const mins = Math.floor((seconds % 3600) / 60);
  const secs = Math.floor(seconds % 60);
  
  const paddedSecs = secs < 10 ? '0' + secs : secs;
  if (hrs > 0) {
    const paddedMins = mins < 10 ? '0' + mins : mins;
    return `${hrs}:${paddedMins}:${paddedSecs}`;
  }
  return `${mins}:${paddedSecs}`;
}

function escapeHtml(str) {
  if (!str) return '';
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#039;');
}

function escapeJsStr(str) {
  if (!str) return '';
  return str.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/"/g, '&quot;');
}

// RECYCLE BIN FUNCTIONALITY
async function loadRecycleBin() {
  const tbody = document.getElementById('recycle-table-body');
  if (!tbody) return;
  tbody.innerHTML = '<tr><td colspan="5" class="text-center p-4">Loading recycle bin...</td></tr>';
  try {
    const items = await apiCall('/api/recycle-bin');
    tbody.innerHTML = '';
    if (items.length === 0) {
      tbody.innerHTML = '<tr><td colspan="5" class="text-center p-4 text-muted">Recycle bin is empty</td></tr>';
      return;
    }
    items.forEach(item => {
      const tr = document.createElement('tr');
      const sizeStr = item.isDirectory ? '--' : formatBytes(item.fileSize || 0);
      const icon = item.isDirectory ? 'folder' : 'file';
      const dateStr = new Date(item.deletedAt).toLocaleString();

      tr.innerHTML = `
        <td>
          <div style="display:flex; align-items:center; gap:8px;">
            <i data-lucide="${icon}" style="width:18px; height:18px; color: var(--primary);"></i>
            <span>${escapeHtml(item.fileName)}</span>
          </div>
        </td>
        <td>${escapeHtml(item.originalPath)}</td>
        <td>${sizeStr}</td>
        <td>${dateStr}</td>
        <td>
          <div style="display:flex; gap:8px;">
            <button class="btn btn-secondary btn-sm" onclick="restoreRecycleItem(${item.id})" style="display:flex; align-items:center; gap:4px; padding: 4px 8px;">
              <i data-lucide="rotate-ccw" style="width:14px; height:14px;"></i>
              <span>Restore</span>
            </button>
            <button class="btn btn-secondary btn-sm text-error" onclick="deleteRecycleItemPermanently(${item.id})" style="display:flex; align-items:center; gap:4px; padding: 4px 8px;">
              <i data-lucide="trash-2" style="width:14px; height:14px;"></i>
              <span>Delete</span>
            </button>
          </div>
        </td>
      `;
      tbody.appendChild(tr);
    });
    lucide.createIcons();
  } catch (err) {
    tbody.innerHTML = `<tr><td colspan="5" class="error-message">Failed to load recycle bin: ${err.message}</td></tr>`;
  }
}

async function restoreRecycleItem(id) {
  try {
    await apiCall(`/api/recycle-bin/restore/${id}`, { method: 'POST' });
    loadRecycleBin();
  } catch (err) {
    alert('Failed to restore item: ' + err.message);
  }
}

async function deleteRecycleItemPermanently(id) {
  if (!confirm('Are you sure you want to permanently delete this item? This action cannot be undone.')) return;
  try {
    await apiCall(`/api/recycle-bin/${id}`, { method: 'DELETE' });
    loadRecycleBin();
  } catch (err) {
    alert('Failed to delete item: ' + err.message);
  }
}

async function handleEmptyRecycleBin() {
  if (!confirm('Are you sure you want to permanently delete all items in your recycle bin? This action cannot be undone.')) return;
  try {
    await apiCall('/api/recycle-bin/clean', { method: 'DELETE' });
    loadRecycleBin();
  } catch (err) {
    alert('Failed to empty recycle bin: ' + err.message);
  }
}

window.restoreRecycleItem = restoreRecycleItem;
window.deleteRecycleItemPermanently = deleteRecycleItemPermanently;
window.handleEmptyRecycleBin = handleEmptyRecycleBin;

let eventSource = null;

function initSse() {
  if (eventSource) {
    eventSource.close();
  }

  eventSource = new EventSource(`/api/events?token=${encodeURIComponent(state.token)}`);

  eventSource.addEventListener('fs-change', (e) => {
    try {
      const data = JSON.parse(e.data);
      // If we are looking at the directory where the change happened
      if (data.parentPath === state.currentPath) {
        browsePath(state.currentPath);
      }
    } catch (err) {
      console.error('Failed to parse SSE event data:', err);
    }
  });

  eventSource.addEventListener('file-op-progress', (e) => {
    try {
      const data = JSON.parse(e.data);
      updateFileOpProgressUI(data);
    } catch (err) {
      console.error('Failed to parse SSE file-op-progress event data:', err);
    }
  });

  eventSource.addEventListener('theme-update', (e) => {
    if (state.user) return;
    try {
      const data = JSON.parse(e.data);
      const theme = data.theme || 'deep-ocean';
      
      const allThemes = [
        'theme-cyber-sakura', 'theme-deep-ocean', 'theme-midnight-azure', 'theme-carbon-gray',
        'theme-aura-green', 'theme-neon-violet', 'theme-sunset-orange', 'theme-crimson-red',
        'theme-forest-lagoon', 'theme-golden-amber'
      ];
      allThemes.forEach(t => document.body.classList.remove(t));
      document.body.classList.add(`theme-${theme}`);
      
      document.querySelectorAll('.theme-card').forEach(card => card.classList.remove('active'));
      const activeCard = document.getElementById(`theme-card-${theme}`);
      if (activeCard) {
        activeCard.classList.add('active');
      }
    } catch (err) {
      console.error('Failed to parse SSE theme update event:', err);
    }
  });

  eventSource.addEventListener('ui-style-update', (e) => {
    if (state.user) return;
    try {
      const data = JSON.parse(e.data);
      const style = data.style || 'glassmorphism';
      
      const allStyles = [
        'style-glassmorphism', 'style-minimalist', 'style-retro-terminal', 'style-vaporwave-dream',
        'style-cyberpunk', 'style-material-design', 'style-nebula-space', 'style-steel-chrome',
        'style-nordic-aurora', 'style-aero-classic'
      ];
      allStyles.forEach(s => document.body.classList.remove(s));
      document.body.classList.add(`style-${style}`);
      
      document.querySelectorAll('.ui-style-card').forEach(card => card.classList.remove('active'));
      const activeCard = document.getElementById(`ui-style-card-${style}`);
      if (activeCard) {
        activeCard.classList.add('active');
      }
    } catch (err) {
      console.error('Failed to parse SSE ui-style update event:', err);
    }
  });

  eventSource.onerror = (err) => {
    console.warn('SSE connection error, closing. Browser will auto-reconnect.', err);
  };
}

function closeSse() {
  if (eventSource) {
    eventSource.close();
    eventSource = null;
  }
}

async function applyTheme() {
  let systemTheme = 'deep-ocean';
  try {
    const res = await fetch('/api/theme');
    if (res.ok) {
      const data = await res.json();
      systemTheme = data.theme || 'deep-ocean';
      
      // Highlight active system theme card
      document.querySelectorAll('.theme-card:not(.profile-theme-card)').forEach(card => card.classList.remove('active'));
      const activeCard = document.getElementById(`theme-card-${systemTheme}`);
      if (activeCard) {
        activeCard.classList.add('active');
      }
    }
  } catch (err) {
    console.error('Failed to load theme:', err);
  }

  if (state.user) {
    applyUserThemeAndStyle();
    return;
  }

  // Remove all theme classes
  const allThemes = [
    'theme-cyber-sakura', 'theme-deep-ocean', 'theme-midnight-azure', 'theme-carbon-gray',
    'theme-aura-green', 'theme-neon-violet', 'theme-sunset-orange', 'theme-crimson-red',
    'theme-forest-lagoon', 'theme-golden-amber'
  ];
  allThemes.forEach(t => document.body.classList.remove(t));
  // Add active theme class
  document.body.classList.add(`theme-${systemTheme}`);
}

async function setSystemTheme(themeName) {
  try {
    const res = await apiCall('/api/theme', {
      method: 'PUT',
      body: JSON.stringify({ theme: themeName })
    });
    if (res.success) {
      // Highlight active system theme card immediately
      document.querySelectorAll('.theme-card:not(.profile-theme-card)').forEach(card => card.classList.remove('active'));
      const activeCard = document.getElementById(`theme-card-${themeName}`);
      if (activeCard) {
        activeCard.classList.add('active');
      }

      if (state.user) {
        await setProfileTheme(themeName);
      } else {
        await applyTheme();
      }
    }
  } catch (err) {
    alert(err.message || 'Failed to update theme');
  }
}

async function applyUiStyle() {
  let systemStyle = 'glassmorphism';
  try {
    const res = await fetch('/api/ui-style');
    if (res.ok) {
      const data = await res.json();
      systemStyle = data.style || 'glassmorphism';
      
      // Highlight active system UI style card
      document.querySelectorAll('.ui-style-card:not(.profile-ui-style-card)').forEach(card => card.classList.remove('active'));
      const activeCard = document.getElementById(`ui-style-card-${systemStyle}`);
      if (activeCard) {
        activeCard.classList.add('active');
      }
    }
  } catch (err) {
    console.error('Failed to load UI style:', err);
  }

  if (state.user) {
    applyUserThemeAndStyle();
    return;
  }

  // Remove all UI style classes
  const allStyles = [
    'style-glassmorphism', 'style-minimalist', 'style-retro-terminal', 'style-vaporwave-dream',
    'style-cyberpunk', 'style-material-design', 'style-nebula-space', 'style-steel-chrome',
    'style-nordic-aurora', 'style-aero-classic'
  ];
  allStyles.forEach(s => document.body.classList.remove(s));
  // Add active style class
  document.body.classList.add(`style-${systemStyle}`);
}

async function setSystemUiStyle(styleName) {
  try {
    const res = await apiCall('/api/ui-style', {
      method: 'PUT',
      body: JSON.stringify({ style: styleName })
    });
    if (res.success) {
      // Highlight active system UI style card immediately
      document.querySelectorAll('.ui-style-card:not(.profile-ui-style-card)').forEach(card => card.classList.remove('active'));
      const activeCard = document.getElementById(`ui-style-card-${styleName}`);
      if (activeCard) {
        activeCard.classList.add('active');
      }

      if (state.user) {
        await setProfileUiStyle(styleName);
      } else {
        await applyUiStyle();
      }
    }
  } catch (err) {
    alert(err.message || 'Failed to update UI style');
  }
}

function updateUserProfileUI() {
  if (state.user) {
    const username = state.user.username;
    const avatarUrl = `/api/users/avatar/${username}?v=${Date.now()}`;
    
    const avatarEl = document.getElementById('user-avatar');
    if (avatarEl) {
      avatarEl.innerHTML = `<img src="${avatarUrl}" alt="${username}">`;
    }

    document.getElementById('user-display-name').innerText = username;
    document.getElementById('user-display-role').innerText = state.user.role === 'admin' ? 'Owner / Admin' : 'User';

    // Update settings preview
    const previewImg = document.getElementById('profile-avatar-preview');
    if (previewImg) {
      previewImg.src = avatarUrl;
      previewImg.style.display = 'block';
    }
    const placeholderDiv = document.getElementById('profile-avatar-placeholder');
    if (placeholderDiv) {
      placeholderDiv.style.display = 'none';
    }
  }
}

async function handleAvatarUpload(e) {
  const file = e.target.files[0];
  if (!file) return;

  const errorEl = document.getElementById('profile-error');
  const successEl = document.getElementById('profile-success');
  errorEl.innerText = '';
  successEl.style.display = 'none';

  if (file.size > 5 * 1024 * 1024) {
    errorEl.innerText = 'File size exceeds 5MB limit';
    e.target.value = '';
    return;
  }

  const formData = new FormData();
  formData.append('file', file);

  try {
    const res = await apiCall('/api/auth/profile/avatar', {
      method: 'POST',
      body: formData
    });

    successEl.innerText = 'Profile picture updated successfully!';
    successEl.style.display = 'block';
    updateUserProfileUI();
  } catch (err) {
    errorEl.innerText = err.message || 'Failed to upload profile picture';
  } finally {
    e.target.value = '';
  }
}

async function handleAvatarDelete() {
  const errorEl = document.getElementById('profile-error');
  const successEl = document.getElementById('profile-success');
  errorEl.innerText = '';
  successEl.style.display = 'none';

  if (!confirm('Are you sure you want to remove your profile picture?')) {
    return;
  }

  try {
    await apiCall('/api/auth/profile/avatar', {
      method: 'DELETE'
    });

    successEl.innerText = 'Profile picture removed successfully!';
    successEl.style.display = 'block';
    updateUserProfileUI();
  } catch (err) {
    errorEl.innerText = err.message || 'Failed to remove profile picture';
  }
}

async function handleUpdateProfile(e) {
  e.preventDefault();
  const username = document.getElementById('profile-username').value;
  const password = document.getElementById('profile-password').value;
  const errorEl = document.getElementById('profile-error');
  const successEl = document.getElementById('profile-success');

  errorEl.innerText = '';
  successEl.style.display = 'none';

  try {
    const payload = { username };
    if (password.trim()) {
      payload.password = password;
    }

    const res = await apiCall('/api/auth/profile', {
      method: 'PUT',
      body: JSON.stringify(payload)
    });

    // Update state
    state.token = res.token;
    state.user.username = res.username;
    if (res.plainPassword) {
      state.user.plainPassword = res.plainPassword;
    }
    
    // Save to local/session storage
    const rememberMe = localStorage.getItem('token') !== null;
    if (rememberMe) {
      localStorage.setItem('token', res.token);
      localStorage.setItem('user', JSON.stringify(state.user));
    } else {
      sessionStorage.setItem('token', res.token);
      sessionStorage.setItem('user', JSON.stringify(state.user));
    }

    // Update UI
    updateUserProfileUI();
    document.getElementById('profile-current-password-display').innerText = res.plainPassword || username;
    document.getElementById('profile-password').value = '';
    
    successEl.style.display = 'block';
    setTimeout(() => {
      successEl.style.display = 'none';
    }, 5000);
  } catch (err) {
    errorEl.innerText = err.message || 'Failed to update profile';
  }
}

window.applyTheme = applyTheme;
window.setSystemTheme = setSystemTheme;
window.applyUiStyle = applyUiStyle;
window.setSystemUiStyle = setSystemUiStyle;
window.updateUserProfileUI = updateUserProfileUI;
window.handleUpdateProfile = handleUpdateProfile;

function applyUserThemeAndStyle() {
  let theme = 'carbon-gray';
  let style = 'steel-chrome';
  
  if (state.user) {
    theme = state.user.theme || 'carbon-gray';
    style = state.user.uiStyle || 'steel-chrome';
  }

  theme = theme.trim();
  style = style.trim();

  // Apply Theme
  const allThemes = [
    'theme-cyber-sakura', 'theme-deep-ocean', 'theme-midnight-azure', 'theme-carbon-gray',
    'theme-aura-green', 'theme-neon-violet', 'theme-sunset-orange', 'theme-crimson-red',
    'theme-forest-lagoon', 'theme-golden-amber'
  ];
  allThemes.forEach(t => document.body.classList.remove(t));
  document.body.classList.add(`theme-${theme}`);
  
  // Highlight profile theme card if visible
  document.querySelectorAll('.profile-theme-card').forEach(card => card.classList.remove('active'));
  const activeThemeCard = document.getElementById(`profile-theme-card-${theme}`);
  if (activeThemeCard) {
    activeThemeCard.classList.add('active');
  }

  // Apply UI Style
  const allStyles = [
    'style-glassmorphism', 'style-minimalist', 'style-retro-terminal', 'style-vaporwave-dream',
    'style-cyberpunk', 'style-material-design', 'style-nebula-space', 'style-steel-chrome',
    'style-nordic-aurora', 'style-aero-classic'
  ];
  allStyles.forEach(s => document.body.classList.remove(s));
  document.body.classList.add(`style-${style}`);
  
  // Highlight profile UI style card if visible
  document.querySelectorAll('.profile-ui-style-card').forEach(card => card.classList.remove('active'));
  const activeStyleCard = document.getElementById(`profile-ui-style-card-${style}`);
  if (activeStyleCard) {
    activeStyleCard.classList.add('active');
  }
}

async function setProfileTheme(themeName) {
  if (!state.user) return;
  try {
    const res = await apiCall('/api/auth/profile', {
      method: 'PUT',
      body: JSON.stringify({
        username: state.user.username,
        theme: themeName
      })
    });
    
    state.token = res.token;
    state.user.username = res.username;
    state.user.theme = res.theme;
    state.user.uiStyle = res.uiStyle;
    
    // Save to storage
    const rememberMe = localStorage.getItem('token') !== null;
    if (rememberMe) {
      localStorage.setItem('token', res.token);
      localStorage.setItem('user', JSON.stringify(state.user));
    } else {
      sessionStorage.setItem('token', res.token);
      sessionStorage.setItem('user', JSON.stringify(state.user));
    }
    
    applyUserThemeAndStyle();
  } catch (err) {
    console.error('Failed to update profile theme:', err);
  }
}

async function setProfileUiStyle(styleName) {
  if (!state.user) return;
  try {
    const res = await apiCall('/api/auth/profile', {
      method: 'PUT',
      body: JSON.stringify({
        username: state.user.username,
        uiStyle: styleName
      })
    });
    
    state.token = res.token;
    state.user.username = res.username;
    state.user.theme = res.theme;
    state.user.uiStyle = res.uiStyle;
    
    // Save to storage
    const rememberMe = localStorage.getItem('token') !== null;
    if (rememberMe) {
      localStorage.setItem('token', res.token);
      localStorage.setItem('user', JSON.stringify(state.user));
    } else {
      sessionStorage.setItem('token', res.token);
      sessionStorage.setItem('user', JSON.stringify(state.user));
    }
    
    applyUserThemeAndStyle();
  } catch (err) {
    console.error('Failed to update profile UI style:', err);
  }
}

window.applyUserThemeAndStyle = applyUserThemeAndStyle;
window.setProfileTheme = setProfileTheme;
window.setProfileUiStyle = setProfileUiStyle;

// ==========================================================================
// FILE AND FOLDER PICKER FOR PERMISSIONS
// ==========================================================================

function openPickerModal() {
  const modal = document.getElementById('modal-picker');
  if (!modal) return;
  
  modal.classList.add('active');
  
  // Populate the drive select dropdown
  const driveSelect = document.getElementById('picker-drive-select');
  if (driveSelect) {
    driveSelect.innerHTML = '';
    state.roots.forEach(root => {
      const opt = document.createElement('option');
      opt.value = root.path;
      opt.innerText = root.name;
      driveSelect.appendChild(opt);
    });
  }

  // Set initial path: use the current explorer path or SAKURA_ROOT as fallback
  state.pickerCurrentPath = state.currentPath || '/home/sakura';
  state.pickerSelectedPaths = []; // Nothing selected initially
  
  // Synchronize dropdown value
  syncPickerDriveSelect(state.pickerCurrentPath);
  
  loadPickerDirectory(state.pickerCurrentPath);
}

function syncPickerDriveSelect(path) {
  const driveSelect = document.getElementById('picker-drive-select');
  if (!driveSelect || !state.roots.length) return;
  
  const matchedRoot = findRootForPath(path);
  if (matchedRoot) {
    driveSelect.value = matchedRoot.path;
  }
}

function closePickerModal() {
  const modal = document.getElementById('modal-picker');
  if (modal) {
    modal.classList.remove('active');
  }
}

async function loadPickerDirectory(path) {
  const listContainer = document.getElementById('picker-list');
  const breadcrumbsContainer = document.getElementById('picker-breadcrumbs');
  
  if (!listContainer || !breadcrumbsContainer) return;
  
  listContainer.innerHTML = '<div style="padding: 10px; color: var(--text-muted); text-align: center;">Loading folder contents...</div>';
  breadcrumbsContainer.innerHTML = '';
  
  state.pickerCurrentPath = path;
  syncPickerDriveSelect(path);
  
  // Clear active selections on directory change
  state.pickerSelectedPaths = [];
  updatePickerSelectedDisplay();

  try {
    const data = await apiCall(`/api/files/browse?path=${encodeURIComponent(path)}`);
    listContainer.innerHTML = '';
    
    // Render breadcrumbs
    renderPickerBreadcrumbs(path);
    
    // Up directory navigation item
    const matchedRoot = findRootForPath(path);
    const parentPath = getParentDirectory(path);
    if (parentPath && matchedRoot && path !== matchedRoot.path) {
      const upItem = document.createElement('div');
      upItem.className = 'picker-item';
      upItem.style.cssText = 'display: flex; align-items: center; gap: 8px; padding: 8px; border-radius: 4px; cursor: pointer; color: var(--primary);';
      upItem.innerHTML = `<i data-lucide="folder-up" style="width: 16px; height: 16px;"></i> <span>.. (Parent Directory)</span>`;
      upItem.addEventListener('click', () => {
        loadPickerDirectory(parentPath);
      });
      listContainer.appendChild(upItem);
    }
    
    // Sort directories first, then files
    const folders = data.files.filter(f => !f.isFile);
    const files = data.files.filter(f => f.isFile);
    const items = [...folders, ...files];
    
    if (items.length === 0) {
      const emptyMsg = document.createElement('div');
      emptyMsg.style.cssText = 'padding: 15px; color: var(--text-muted); text-align: center; font-size: 13px;';
      emptyMsg.innerText = 'This folder is empty';
      listContainer.appendChild(emptyMsg);
    } else {
      items.forEach(file => {
        const itemEl = document.createElement('div');
        itemEl.className = 'picker-item';
        itemEl.style.cssText = 'display: flex; align-items: center; justify-content: space-between; padding: 6px 8px; border-radius: 4px; cursor: pointer; transition: background var(--transition-fast);';
        
        // Build absolute path for the item
        const itemPath = path === '/' ? `/${file.name}` : `${path}/${file.name}`;
        
        // Icon
        const iconName = file.isFile ? 'file' : 'folder';
        const iconColor = file.isFile ? 'var(--text-muted)' : 'var(--primary)';
        
        // Left side content: checkbox + icon + name
        const itemLeft = document.createElement('div');
        itemLeft.style.cssText = 'display: flex; align-items: center; gap: 8px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; flex-grow: 1;';
        
        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.style.cssText = 'margin: 0; cursor: pointer; width: 14px; height: 14px;';
        checkbox.checked = state.pickerSelectedPaths.includes(itemPath);
        
        // Prevent event bubbling on checkbox click
        checkbox.addEventListener('click', (e) => {
          e.stopPropagation();
          toggleItemSelection(itemPath, itemEl, checkbox);
        });
        
        itemLeft.appendChild(checkbox);
        
        const iconHTML = `<i data-lucide="${iconName}" style="width: 16px; height: 16px; color: ${iconColor}; flex-shrink: 0;"></i>`;
        const iconWrapper = document.createElement('div');
        iconWrapper.innerHTML = iconHTML;
        itemLeft.appendChild(iconWrapper.firstChild);
        
        const nameSpan = document.createElement('span');
        nameSpan.style.cssText = 'font-size: 13px; overflow: hidden; text-overflow: ellipsis;';
        nameSpan.innerText = file.name;
        itemLeft.appendChild(nameSpan);
        
        itemEl.appendChild(itemLeft);
        
        // Toggle selection on whole row click
        itemEl.addEventListener('click', (e) => {
          e.stopPropagation();
          toggleItemSelection(itemPath, itemEl, checkbox);
        });
        
        // If folder, double-click navigates inside
        if (!file.isFile) {
          itemEl.addEventListener('dblclick', (e) => {
            e.stopPropagation();
            loadPickerDirectory(itemPath);
          });
          
          // Helper navigate icon on the right
          const navIcon = document.createElement('i');
          navIcon.setAttribute('data-lucide', 'chevron-right');
          navIcon.style.cssText = 'width: 14px; height: 14px; color: var(--text-muted); cursor: pointer; padding: 4px;';
          navIcon.addEventListener('click', (e) => {
            e.stopPropagation();
            loadPickerDirectory(itemPath);
          });
          itemEl.appendChild(navIcon);
        }
        
        listContainer.appendChild(itemEl);
      });
    }
    
    if (typeof lucide !== 'undefined') lucide.createIcons({ container: listContainer });
    
  } catch (err) {
    console.error('Failed to load picker directory:', err);
    listContainer.innerHTML = `<div style="padding: 10px; color: var(--red); text-align: center;">Error loading folder: ${err.message || err}</div>`;
  }
}

function toggleItemSelection(itemPath, itemEl, checkbox) {
  const index = state.pickerSelectedPaths.indexOf(itemPath);
  if (index > -1) {
    state.pickerSelectedPaths.splice(index, 1);
    itemEl.style.background = 'transparent';
    checkbox.checked = false;
  } else {
    state.pickerSelectedPaths.push(itemPath);
    itemEl.style.background = 'rgba(255, 74, 136, 0.15)';
    checkbox.checked = true;
  }
  updatePickerSelectedDisplay();
}

function getParentDirectory(path) {
  if (!path || path === '/' || path.indexOf('/') === -1) return null;
  const lastSlash = path.lastIndexOf('/');
  if (lastSlash === 0) return '/';
  return path.substring(0, lastSlash);
}

function renderPickerBreadcrumbs(path) {
  const container = document.getElementById('picker-breadcrumbs');
  if (!container) return;
  
  // Find which root this path belongs to
  const matchedRoot = findRootForPath(path);
  if (!matchedRoot) {
    // If not matching any root, just display the text
    container.innerHTML = `<span>${path}</span>`;
    return;
  }
  
  // Render root badge
  const rootBtn = document.createElement('span');
  rootBtn.style.cssText = 'cursor: pointer; color: var(--primary); font-weight: 500;';
  rootBtn.innerText = matchedRoot.name;
  rootBtn.addEventListener('click', () => loadPickerDirectory(matchedRoot.path));
  container.appendChild(rootBtn);
  
  const relativePart = path.substring(matchedRoot.path.length);
  const parts = relativePart.split('/').filter(p => p !== '');
  
  let currentAccumulatedPath = matchedRoot.path;
  parts.forEach(part => {
    const sep = document.createElement('span');
    sep.innerText = ' / ';
    sep.style.color = 'var(--text-muted)';
    container.appendChild(sep);
    
    currentAccumulatedPath += '/' + part;
    const thisPath = currentAccumulatedPath;
    
    const segment = document.createElement('span');
    segment.style.cssText = 'cursor: pointer; color: var(--text-primary);';
    segment.innerText = part;
    segment.addEventListener('click', () => loadPickerDirectory(thisPath));
    container.appendChild(segment);
  });
}

function updatePickerSelectedDisplay() {
  const display = document.getElementById('picker-selected-display');
  if (!display) return;
  
  if (state.pickerSelectedPaths.length > 0) {
    display.innerHTML = `Selected (${state.pickerSelectedPaths.length} items): <strong style="color: var(--primary); font-size: 11px;">${state.pickerSelectedPaths.join(', ')}</strong>`;
  } else {
    display.innerHTML = `Selected (Current Folder): <strong style="color: var(--primary);">${state.pickerCurrentPath}</strong>`;
  }
}

function confirmPickerSelection() {
  const inputEl = document.getElementById('rule-path');
  if (!inputEl) return;
  
  if (state.pickerSelectedPaths.length > 0) {
    inputEl.value = state.pickerSelectedPaths.join(', ');
  } else {
    inputEl.value = state.pickerCurrentPath;
  }
  closePickerModal();
}






