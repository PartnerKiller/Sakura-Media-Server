// GLOBAL STATE
let state = {
  token: localStorage.getItem('token') || sessionStorage.getItem('token'),
  user: JSON.parse(localStorage.getItem('user') || sessionStorage.getItem('user')),
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
  isUploadCancelled: false
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

  if (response.status === 401 || response.status === 403) {
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

function initApp() {
  lucide.createIcons();
  
  if (state.token && state.user) {
    showDashboard();
  } else {
    showLogin();
  }

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

  // Bind Sidebar Navigation
  safeAddListener('nav-explorer', 'click', () => switchPanel('explorer'));
  safeAddListener('nav-users', 'click', () => switchPanel('users'));
  safeAddListener('nav-server', 'click', () => switchPanel('server'));
  safeAddListener('nav-recycle-bin', 'click', () => switchPanel('recycle-bin'));
  safeAddListener('btn-empty-recycle', 'click', handleEmptyRecycleBin);

  // Bind Mobile Bottom Navigation
  safeAddListener('mobile-nav-explorer', 'click', () => switchPanel('explorer'));
  safeAddListener('mobile-nav-users', 'click', () => switchPanel('users'));
  safeAddListener('mobile-nav-server', 'click', () => switchPanel('server'));
  safeAddListener('mobile-nav-logout', 'click', logout);

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
  });
}

// AUTHENTICATION FLOWS
function showLogin() {
  document.getElementById('login-container').classList.add('active');
  document.getElementById('dashboard-container').classList.remove('active');
  document.getElementById('login-error').innerText = '';
}

function showDashboard() {
  document.getElementById('login-container').classList.remove('active');
  document.getElementById('dashboard-container').classList.add('active');
  
  // Update user profile info in sidebar
  document.getElementById('user-avatar').innerText = state.user.username[0].toUpperCase();
  document.getElementById('user-display-name').innerText = state.user.username;
  document.getElementById('user-display-role').innerText = state.user.role === 'admin' ? 'Owner / Admin' : 'User';

  // Toggle Admin Section Visibility
  if (state.user.role === 'admin') {
    document.querySelectorAll('.admin-only').forEach(el => el.style.display = 'flex');
  } else {
    document.querySelectorAll('.admin-only').forEach(el => el.style.display = 'none');
  }

  switchPanel('explorer');
  loadRoots();
  initSse();
}

async function handleLogin(e) {
  e.preventDefault();
  const usernameEl = document.getElementById('username');
  const passwordEl = document.getElementById('password');
  const errorEl = document.getElementById('login-error');

  errorEl.innerText = '';
  
  try {
    const res = await apiCall('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({
        username: usernameEl.value,
        password: passwordEl.value
      })
    });

    state.token = res.token;
    state.user = res.user;
    
    const rememberMe = document.getElementById('remember-me').checked;
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
    usernameEl.value = '';
    passwordEl.value = '';
    
    // Reset toggle to password mode
    const pwdInput = document.getElementById('password');
    pwdInput.type = 'password';
    const toggleBtn = document.getElementById('btn-toggle-login-password');
    if (toggleBtn) {
      toggleBtn.innerHTML = '<i data-lucide="eye"></i>';
      lucide.createIcons();
    }
    
    showDashboard();
  } catch (err) {
    errorEl.innerText = err.message || 'Login failed';
  }
}

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
  showLogin();
}

// PANEL NAVIGATION
function switchPanel(panelName) {
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
    document.getElementById('panel-recycle-bin').classList.add('active');
    document.getElementById('explorer-actions').style.display = 'none';
    loadRecycleBin();
  }
}

// EXPLORER FUNCTIONALITY
async function loadRoots() {
  try {
    const roots = await apiCall('/api/files/roots');
    state.roots = roots;
    renderRoots();
    
    if (roots.length > 0) {
      selectRoot(roots[0]);
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
  container.innerHTML = '';

  state.roots.forEach(root => {
    const badge = document.createElement('div');
    badge.className = 'root-badge';
    if (state.currentRoot && state.currentRoot.path === root.path) {
      badge.classList.add('active');
    }
    badge.innerHTML = `<i data-lucide="hard-drive"></i> <span>${root.name}</span>`;
    badge.addEventListener('click', () => selectRoot(root));
    container.appendChild(badge);
  });
  lucide.createIcons();
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
  document.getElementById('search-input').value = ''; // clear search
  updateUploadActionsVisibility();
  
  try {
    const res = await apiCall(`/api/files/browse?path=${encodeURIComponent(targetPath)}`);
    state.files = res.files;
    processAndRenderFiles();
    renderBreadcrumbs();
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
      } else if (['mp4', 'mkv', 'webm', 'avi', 'mov'].includes(ext)) {
        category = 'video';
      } else if (['png', 'jpg', 'jpeg', 'gif', 'webp'].includes(ext)) {
        category = 'image';
      } else if (['mp3', 'wav', 'ogg', 'flac', 'm4a'].includes(ext)) {
        category = 'audio';
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
}

function renderFiles(files) {
  const grid = document.getElementById('files-grid-container');
  const emptyState = document.getElementById('empty-state');
  grid.innerHTML = '';

  if (files.length === 0) {
    emptyState.style.display = 'flex';
    return;
  }
  emptyState.style.display = 'none';

  if (state.viewMode === 'list') {
    grid.classList.add('list-view');
  } else {
    grid.classList.remove('list-view');
  }

  files.forEach(file => {
    const card = document.createElement('div');
    card.className = 'file-card';
    
    // Determine card category class and icon
    let category = 'file';
    let icon = 'file-text';
    const ext = file.name.split('.').pop().toLowerCase();
    
    if (!file.isFile) {
      category = 'folder';
      icon = 'folder';
    } else if (['mp4', 'mkv', 'webm', 'avi', 'mov'].includes(ext)) {
      category = 'video';
      icon = 'video';
    } else if (['png', 'jpg', 'jpeg', 'gif', 'webp'].includes(ext)) {
      category = 'image';
      icon = 'image';
    } else if (['mp3', 'wav', 'ogg', 'flac', 'm4a'].includes(ext)) {
      category = 'audio';
      icon = 'music';
    }

    card.classList.add(category);
    
    const filePath = `${state.currentPath}/${file.name}`;
    
    // Size formatting helper
    const formattedSize = file.isFile ? formatBytes(file.size) : '';
    const formattedDate = file.mtime ? formatDate(file.mtime) : '';

    card.innerHTML = `
      <div class="file-icon-wrapper">
        <i data-lucide="${icon}"></i>
      </div>
      <div class="file-card-info">
        <div class="file-name" title="${file.name}">${file.name}</div>
        <div class="file-meta-size">${file.isFile ? formattedSize : 'Folder'}</div>
        <div class="file-meta-date">${formattedDate}</div>
      </div>
      <div class="file-actions">
        ${file.isFile ? `
          <button class="btn-card-action btn-download" onclick="handleDownloadFile(event, '${filePath.replace(/'/g, "\\'")}')" title="Download">
            <i data-lucide="download"></i>
          </button>
        ` : `
          <button class="btn-card-action btn-download" onclick="handleDownloadFolder(event, '${filePath.replace(/'/g, "\\'")}')" title="Download Folder as ZIP">
            <i data-lucide="download"></i>
          </button>
        `}
        <button class="btn-card-action" onclick="handleDeleteFile(event, '${filePath.replace(/'/g, "\\'")}')" title="Delete">
          <i data-lucide="trash-2"></i>
        </button>
      </div>
    `;

    // Click handler: Double-click / Click to open
    card.addEventListener('click', (e) => {
      // Prevent action button click from triggering card click
      if (e.target.closest('.btn-card-action')) return;
      
      if (!file.isFile) {
        browsePath(filePath);
      } else {
        openMedia(filePath, file.name, category);
      }
    });

    grid.appendChild(card);
  });
  
  lucide.createIcons();
}

function renderBreadcrumbs() {
  const container = document.getElementById('breadcrumbs-container');
  container.innerHTML = '';
  
  if (!state.currentRoot) return;

  // Make paths relative to root for breadcrumb display
  const rootPath = state.currentRoot.path;
  const rootName = state.currentRoot.name;
  
  // Create first root element
  const rootItem = document.createElement('span');
  rootItem.className = 'breadcrumb-item';
  rootItem.innerText = rootName;
  rootItem.addEventListener('click', () => browsePath(rootPath));
  container.appendChild(rootItem);

  if (state.currentPath !== rootPath) {
    // Get subpath relative to root path
    const relativePart = state.currentPath.substring(rootPath.length);
    const parts = relativePart.split('/').filter(p => p !== '');
    
    let accumulatedPath = rootPath;
    
    parts.forEach((part, index) => {
      // Separator
      const separator = document.createElement('span');
      separator.className = 'breadcrumb-separator';
      separator.innerHTML = '<i data-lucide="chevron-right"></i>';
      container.appendChild(separator);

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
  }
  lucide.createIcons();
}

function filterFiles() {
  processAndRenderFiles();
}

// MEDIA HANDLERS
function openMedia(filePath, fileName, category) {
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

function handleDownloadFile(e, filePath) {
  e.stopPropagation(); // prevent card click
  window.open(`/api/files/download?path=${encodeURIComponent(filePath)}&token=${state.token}`, '_blank');
}
window.handleDownloadFile = handleDownloadFile;

function handleDownloadFolder(e, filePath) {
  e.stopPropagation(); // prevent card click
  window.open(`/api/files/download-folder?path=${encodeURIComponent(filePath)}&token=${state.token}`, '_blank');
}
window.handleDownloadFolder = handleDownloadFolder;

const CHUNK_SIZE = 8 * 1024 * 1024; // 8MB chunks (fits comfortably within Cloudflare limit)

async function uploadFileInChunks(file, basePath, relPath, onProgress) {
  if (state.isUploadCancelled) {
    throw new Error('Upload cancelled');
  }

  const uploadId = Math.random().toString(36).substring(2, 15) + Date.now().toString(36);
  const totalChunks = Math.ceil(file.size / CHUNK_SIZE);

  for (let chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
    if (state.isUploadCancelled) {
      throw new Error('Upload cancelled');
    }

    const start = chunkIndex * CHUNK_SIZE;
    const end = Math.min(start + CHUNK_SIZE, file.size);
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
          onProgress(totalLoaded / file.size);
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

  filenameEl.innerText = `Preparing folder upload... (0/${totalFiles} files)`;
  percentEl.innerText = '0%';
  fillEl.style.width = '0%';
  progressContainer.style.display = 'block';
  lucide.createIcons();

  for (let i = 0; i < totalFiles; i++) {
    if (state.isUploadCancelled) break;
    const file = files[i];
    const relPath = file.webkitRelativePath || file.name;
    
    try {
      await uploadFileInChunks(file, state.currentPath, relPath, (fileRatio) => {
        if (state.isUploadCancelled) return;
        const overallPercent = Math.round(((i + fileRatio) / totalFiles) * 100);
        filenameEl.innerText = `Uploading: ${relPath} (${i + 1}/${totalFiles})`;
        percentEl.innerText = `${overallPercent}%`;
        fillEl.style.width = `${overallPercent}%`;
      });
    } catch (err) {
      if (state.isUploadCancelled || !err || err.message === 'Upload cancelled' || (err.message && err.message.includes('cancelled'))) {
        console.log('Folder upload cancelled by user');
        break;
      }
      console.error('File upload error in folder:', err);
    }
  }

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

  filenameEl.innerText = totalFiles === 1 ? files[0].name : `Preparing upload... (0/${totalFiles} files)`;
  percentEl.innerText = '0%';
  fillEl.style.width = '0%';
  progressContainer.style.display = 'block';
  lucide.createIcons();

  for (let i = 0; i < totalFiles; i++) {
    if (state.isUploadCancelled) break;
    const file = files[i];
    try {
      await uploadFileInChunks(file, state.currentPath, '', (fileRatio) => {
        if (state.isUploadCancelled) return;
        const overallPercent = Math.round(((i + fileRatio) / totalFiles) * 100);
        filenameEl.innerText = totalFiles === 1 ? file.name : `Uploading: ${file.name} (${i + 1}/${totalFiles})`;
        percentEl.innerText = `${overallPercent}%`;
        fillEl.style.width = `${overallPercent}%`;
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
  const html = homeHtml + storageHtml;

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
      
    const configurePermsBtn = u.role !== 'admin'
      ? `<button class="btn btn-primary" onclick="openPermissionsModal(${u.id}, '${u.username}')">
           <i data-lucide="key" style="width:16px; height:16px;"></i>
           <span>Permissions</span>
         </button>`
      : '<span class="text-muted" style="font-size: 13px;">Administrator</span>';

    const editBtn = `<button class="btn btn-secondary" onclick="openEditUserModal(${u.id}, '${u.username}', '${u.role}', ${u.bandwidthLimit || 0})">
                       <i data-lucide="edit" style="width:16px; height:16px;"></i>
                       <span>Edit</span>
                     </button>`;

    tr.innerHTML = `
      <td>${u.username}</td>
      <td><span class="role-badge ${u.role}">${u.role}</span></td>
      <td>${u.role === 'admin' ? 'All (Full access)' : permCount + ' path rule(s)'}</td>
      <td>${formatBandwidth(u.bandwidthLimit)}</td>
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
  const bandwidthEl = document.getElementById('new-bandwidth');
  const errorEl = document.getElementById('add-user-error');
  
  errorEl.innerText = '';

  try {
    await apiCall('/api/users', {
      method: 'POST',
      body: JSON.stringify({
        username: usernameEl.value.trim(),
        password: passwordEl.value,
        role: roleEl.value,
        bandwidthLimit: bandwidthEl.value ? parseFloat(bandwidthEl.value) : 0
      })
    });

    closeModal('modal-add-user');
    
    usernameEl.value = '';
    passwordEl.value = '';
    roleEl.value = 'user';
    bandwidthEl.value = '';
    
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

  // Check if path is duplicate
  if (state.activePermissionsList.some(r => r.path === pathVal)) {
    alert('Access rule for this path already exists.');
    return;
  }

  state.activePermissionsList.push({
    path: pathVal,
    allowRead: true, // read is implicitly true for folders you have permission to
    allowWrite: writeEl.checked
  });

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
          borderColor: '#ff4a88',
          backgroundColor: 'rgba(255, 74, 136, 0.1)',
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
          borderColor: '#8b5cf6',
          backgroundColor: 'rgba(139, 92, 246, 0.1)',
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
function openEditUserModal(userId, username, role, bandwidthLimit) {
  document.getElementById('edit-user-id').value = userId;
  document.getElementById('edit-username-title').innerText = username;
  document.getElementById('edit-username-display').value = username;
  document.getElementById('edit-password').value = '';
  document.getElementById('edit-password').type = 'password';
  document.getElementById('edit-role').value = role;
  const parsedLimit = parseFloat(bandwidthLimit);
  document.getElementById('edit-bandwidth').value = (parsedLimit && parsedLimit > 0) ? parsedLimit : '';
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

async function handleEditUser(e) {
  e.preventDefault();
  const userId = document.getElementById('edit-user-id').value;
  const password = document.getElementById('edit-password').value;
  const role = document.getElementById('edit-role').value;
  const bandwidth = document.getElementById('edit-bandwidth').value;
  const errorEl = document.getElementById('edit-user-error');

  errorEl.innerText = '';

  try {
    const payload = { 
      role,
      bandwidthLimit: bandwidth ? parseFloat(bandwidth) : 0
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






