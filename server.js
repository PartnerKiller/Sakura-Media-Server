const express = require('express');
const http = require('http');
const path = require('path');
const fs = require('fs');
const jwt = require('jsonwebtoken');
const bcrypt = require('bcryptjs');
const multer = require('multer');
const mime = require('mime-types');
const db = require('./db');
const archiver = require('archiver');
const { exec } = require('child_process');
const os = require('os');

const app = express();
const server = http.createServer(app);
const PORT = 5000;
const JWT_SECRET = process.env.JWT_SECRET || 'sakura-media-server-secret-key-default';

app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

// Roots
const SAKURA_ROOT = '/home/sakura';
const STORAGE_ROOT = '/media/storage';

// Middleware for authentication
function authenticateToken(req, res, next) {
  const authHeader = req.headers['authorization'];
  let token = authHeader && authHeader.split(' ')[1];

  // Accept token from query parameters (critical for direct link downloads and video streaming)
  if (!token && req.query.token) {
    token = req.query.token;
  }

  if (!token) return res.status(401).json({ error: 'Access token required' });

  jwt.verify(token, JWT_SECRET, (err, decoded) => {
    if (err) return res.status(403).json({ error: 'Invalid or expired token' });
    
    // Fetch latest user details from DB to ensure they still exist and check their role
    const user = db.getUserById(decoded.id);
    if (!user) return res.status(403).json({ error: 'User no longer exists' });

    req.user = user;
    next();
  });
}

// Middleware for admin-only operations
function requireAdmin(req, res, next) {
  if (req.user.role !== 'admin') {
    return res.status(403).json({ error: 'Admin access required' });
  }
  next();
}

// Helper to determine if child path is inside parent path
function isSubPath(parent, child) {
  const parentNorm = path.normalize(parent).replace(/\\/g, '/').replace(/\/$/, '');
  const childNorm = path.normalize(child).replace(/\\/g, '/').replace(/\/$/, '');
  return childNorm === parentNorm || childNorm.startsWith(parentNorm + '/');
}

// Helper to resolve user provided path and validate it's under allowed roots
function resolveAndValidatePath(userPath) {
  if (!userPath) return null;
  const resolved = path.resolve(userPath).replace(/\\/g, '/');
  if (isSubPath(SAKURA_ROOT, resolved) || isSubPath(STORAGE_ROOT, resolved)) {
    return resolved;
  }
  return null;
}

// Helper to verify user permissions for a path
function hasPermission(user, targetPath, action) {
  if (user.role === 'admin') {
    return isSubPath(SAKURA_ROOT, targetPath) || isSubPath(STORAGE_ROOT, targetPath);
  }

  const perms = db.getPermissions(user.id);
  for (const p of perms) {
    if (isSubPath(p.path, targetPath)) {
      if (action === 'read' && p.read) return true;
      if (action === 'write' && p.write) return true;
    }
  }
  return false;
}

// Helper to get all roots a user can access
function getAuthorizedRoots(user) {
  if (user.role === 'admin') {
    return [
      { name: 'Home (sakura)', path: SAKURA_ROOT },
      { name: 'Storage', path: STORAGE_ROOT }
    ];
  }
  
  const perms = db.getPermissions(user.id);
  return perms
    .filter(p => p.read)
    .map(p => {
      const name = path.basename(p.path) || p.path;
      return { name, path: p.path };
    });
}

// Configure multer for disk storage based on user permissions
const storage = multer.diskStorage({
  destination: function (req, file, cb) {
    let targetDir = resolveAndValidatePath(req.query.path);
    if (!targetDir || !hasPermission(req.user, targetDir, 'write')) {
      return cb(new Error('Permission denied or invalid path'));
    }

    if (req.query.relativePath) {
      const cleanRelative = path.normalize(req.query.relativePath)
        .replace(/\\/g, '/')
        .replace(/^\//, '')
        .replace(/\.\.\//g, '');
      const dirPart = path.dirname(cleanRelative);

      if (dirPart && dirPart !== '.') {
        const fullSubDir = path.join(targetDir, dirPart).replace(/\\/g, '/');
        const validatedSubDir = resolveAndValidatePath(fullSubDir);

        if (!validatedSubDir || !hasPermission(req.user, validatedSubDir, 'write')) {
          return cb(new Error('Permission denied or invalid path'));
        }

        fs.mkdirSync(validatedSubDir, { recursive: true });
        targetDir = validatedSubDir;
      }
    }
    cb(null, targetDir);
  },
  filename: function (req, file, cb) {
    cb(null, file.originalname);
  }
});
const upload = multer({ storage: storage });

const chunkStorage = multer.diskStorage({
  destination: function (req, file, cb) {
    const uploadId = req.query.uploadId;
    const safeUploadId = path.normalize(uploadId).replace(/\\/g, '/').replace(/^\//, '').replace(/\.\.\//g, '');
    const tempDir = path.join(__dirname, 'temp-chunks', safeUploadId);
    fs.mkdirSync(tempDir, { recursive: true });
    cb(null, tempDir);
  },
  filename: function (req, file, cb) {
    const chunkIndex = req.query.chunkIndex;
    cb(null, `chunk-${chunkIndex}`);
  }
});
const uploadChunk = multer({ storage: chunkStorage });


// --- AUTH APIs ---

app.post('/api/auth/login', async (req, res) => {
  const { username, password } = req.body;
  if (!username || !password) {
    return res.status(400).json({ error: 'Username and password required' });
  }

  const user = db.getUserByUsername(username);
  if (!user) {
    return res.status(401).json({ error: 'Invalid username or password' });
  }

  const validPassword = await bcrypt.compare(password, user.passwordHash);
  if (!validPassword) {
    return res.status(401).json({ error: 'Invalid username or password' });
  }

  const token = jwt.sign({ id: user.id, username: user.username }, JWT_SECRET, { expiresIn: '7d' });
  res.json({
    token,
    user: {
      id: user.id,
      username: user.username,
      role: user.role
    }
  });
});

app.get('/api/auth/me', authenticateToken, (req, res) => {
  res.json({
    id: req.user.id,
    username: req.user.username,
    role: req.user.role
  });
});


// --- USER MANAGEMENT APIs (Admin Only) ---

app.get('/api/users', authenticateToken, requireAdmin, (req, res) => {
  const users = db.getUsers();
  // Include permissions for each user
  const usersWithPerms = users.map(u => ({
    ...u,
    permissions: db.getPermissions(u.id)
  }));
  res.json(usersWithPerms);
});

app.get('/api/admin/storage-analysis', authenticateToken, requireAdmin, (req, res) => {
  exec('df -B1 /home/sakura /media/storage', (err, stdout, stderr) => {
    if (err) {
      console.error('df exec error:', err);
      return res.status(500).json({ error: 'Failed to retrieve storage stats' });
    }

    const lines = stdout.trim().split('\n');
    const parseDfLine = (line) => {
      if (!line) return null;
      const parts = line.trim().split(/\s+/);
      if (parts.length < 6) return null;
      return {
        filesystem: parts[0],
        total: parseInt(parts[1], 10),
        used: parseInt(parts[2], 10),
        available: parseInt(parts[3], 10),
        usePercent: parts[4],
        mountedOn: parts[5]
      };
    };

    const homeStats = parseDfLine(lines[1]);
    const storageStats = parseDfLine(lines[2]);

    res.json({
      home: homeStats ? {
        name: 'Home (sakura)',
        path: '/home/sakura',
        ...homeStats
      } : null,
      storage: storageStats ? {
        name: 'Storage',
        path: '/media/storage',
        ...storageStats
      } : null
    });
  });
});

// CPU stats variables
let lastCpuStats = getCpuStats();

function getCpuStats() {
  const cpus = os.cpus();
  let user = 0, sys = 0, idle = 0, total = 0;
  cpus.forEach(cpu => {
    user += cpu.times.user;
    sys += cpu.times.sys;
    idle += cpu.times.idle;
    total += cpu.times.user + cpu.times.nice + cpu.times.sys + cpu.times.idle + cpu.times.irq;
  });
  return { user, sys, idle, total };
}

function getCpuUsage() {
  const current = getCpuStats();
  const idleDiff = current.idle - lastCpuStats.idle;
  const totalDiff = current.total - lastCpuStats.total;
  lastCpuStats = current;
  if (totalDiff === 0) return 0;
  return Math.min(100, Math.max(0, Math.round((1 - idleDiff / totalDiff) * 100)));
}

// Server metrics endpoint
app.get('/api/admin/server-metrics', authenticateToken, requireAdmin, (req, res) => {
  exec('systemctl --user is-active media-server cloudflared', (err, stdout, stderr) => {
    const statuses = stdout.trim().split('\n');
    const mediaServerStatus = statuses[0] || 'unknown';
    const cloudflaredStatus = statuses[1] || 'unknown';

    const loadAvg = os.loadavg();
    const totalMem = os.totalmem();
    const freeMem = os.freemem();
    const usedMem = totalMem - freeMem;
    const memPercent = Math.round((usedMem / totalMem) * 100);

    const hostUptime = os.uptime();
    const processUptime = process.uptime();

    res.json({
      cpuUsage: getCpuUsage(),
      loadAvg,
      memory: {
        total: totalMem,
        used: usedMem,
        free: freeMem,
        percent: memPercent
      },
      uptime: {
        host: hostUptime,
        process: processUptime
      },
      services: {
        mediaServer: mediaServerStatus,
        cloudflared: cloudflaredStatus
      },
      os: {
        platform: os.platform(),
        release: os.release(),
        arch: os.arch(),
        nodeVersion: process.version
      }
    });
  });
});

// Server active media processes endpoint
app.get('/api/admin/server-processes', authenticateToken, requireAdmin, (req, res) => {
  exec("ps -eo pid,pcpu,pmem,etime,args | grep -E 'ffmpeg|ffprobe' | grep -v grep", (err, stdout, stderr) => {
    if (err && stderr) {
      console.error('ps exec error:', err);
      return res.status(500).json({ error: 'Failed to retrieve process stats' });
    }

    const lines = stdout.trim().split('\n').filter(Boolean);
    const processes = lines.map(line => {
      const parts = line.trim().split(/\s+/);
      const pid = parts[0];
      const cpu = parts[1];
      const mem = parts[2];
      const etime = parts[3];
      const command = parts.slice(4).join(' ');

      return { pid, cpu, mem, etime, command };
    });

    res.json(processes);
  });
});

// Server systemd logs endpoint
app.get('/api/admin/server-logs', authenticateToken, requireAdmin, (req, res) => {
  exec('journalctl --user -u media-server --no-pager -n 100', (err, stdout, stderr) => {
    if (err) {
      console.error('journalctl exec error:', err);
      return res.status(500).json({ error: 'Failed to retrieve logs' });
    }
    res.json({ logs: stdout });
  });
});

// Server action endpoint (restart service, reboot system, kill process)
app.post('/api/admin/server-action', authenticateToken, requireAdmin, (req, res) => {
  const { action, pid } = req.body;
  if (!action) {
    return res.status(400).json({ error: 'Action is required' });
  }

  if (action === 'kill-process') {
    if (!pid) {
      return res.status(400).json({ error: 'PID is required to kill process' });
    }
    exec(`kill -9 ${parseInt(pid, 10)}`, (err, stdout, stderr) => {
      if (err) {
        console.error('kill exec error:', err);
        return res.status(500).json({ error: `Failed to kill process: ${stderr || err.message}` });
      }
      res.json({ success: true, message: `Process ${pid} killed successfully` });
    });
  } else if (action === 'restart-service') {
    res.json({ success: true, message: 'Application service restart scheduled' });
    setTimeout(() => {
      exec('systemctl --user restart media-server');
    }, 500);
  } else if (action === 'reboot-host') {
    res.json({ success: true, message: 'Host machine reboot scheduled' });
    setTimeout(() => {
      exec('systemctl reboot');
    }, 500);
  } else {
    res.status(400).json({ error: `Invalid action: ${action}` });
  }
});

app.post('/api/users', authenticateToken, requireAdmin, async (req, res) => {
  const { username, password, role } = req.body;
  if (!username || !password) {
    return res.status(400).json({ error: 'Username and password required' });
  }

  try {
    const newUser = await db.createUser(username, password, role || 'user');
    res.status(201).json(newUser);
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.delete('/api/users/:id', authenticateToken, requireAdmin, (req, res) => {
  try {
    db.deleteUser(req.params.id);
    res.json({ success: true });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.put('/api/users/:id/permissions', authenticateToken, requireAdmin, (req, res) => {
  const { permissions } = req.body;
  if (!Array.isArray(permissions)) {
    return res.status(400).json({ error: 'Permissions must be an array' });
  }

  // Validate that all permissions are within /home/sakura or /media/storage
  for (const p of permissions) {
    const resolved = path.resolve(p.path).replace(/\\/g, '/');
    if (!isSubPath(SAKURA_ROOT, resolved) && !isSubPath(STORAGE_ROOT, resolved)) {
      return res.status(400).json({ error: `Path must be under /home/sakura or /media/storage: ${p.path}` });
    }
  }

  try {
    const updated = db.setPermissions(req.params.id, permissions);
    res.json({ success: true, permissions: updated });
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});


// --- FILE APIs ---

app.get('/api/files/roots', authenticateToken, (req, res) => {
  res.json(getAuthorizedRoots(req.user));
});

app.get('/api/files/browse', authenticateToken, (req, res) => {
  const reqPath = req.query.path;
  if (!reqPath) {
    return res.status(400).json({ error: 'Path is required' });
  }

  const targetPath = resolveAndValidatePath(reqPath);
  if (!targetPath) {
    return res.status(400).json({ error: 'Invalid path directory' });
  }

  if (!hasPermission(req.user, targetPath, 'read')) {
    return res.status(403).json({ error: 'Permission denied' });
  }

  fs.readdir(targetPath, { withFileTypes: true }, (err, files) => {
    if (err) {
      return res.status(500).json({ error: 'Failed to read directory' });
    }

    const result = files.map(file => {
      const filePath = path.join(targetPath, file.name);
      let stats = { size: 0, mtime: new Date() };
      try {
        stats = fs.statSync(filePath);
      } catch (e) {}

      return {
        name: file.name,
        isFile: file.isFile(),
        size: stats.size,
        mtime: stats.mtime
      };
    });

    // Sort folders first, then files alphabetically
    result.sort((a, b) => {
      if (a.isFile !== b.isFile) {
        return a.isFile ? 1 : -1;
      }
      return a.name.localeCompare(b.name);
    });

    res.json({
      currentPath: targetPath,
      files: result
    });
  });
});

app.get('/api/files/download', authenticateToken, (req, res) => {
  const reqPath = req.query.path;
  if (!reqPath) return res.status(400).json({ error: 'Path is required' });

  const targetPath = resolveAndValidatePath(reqPath);
  if (!targetPath || !fs.existsSync(targetPath) || !fs.statSync(targetPath).isFile()) {
    return res.status(404).json({ error: 'File not found' });
  }

  if (!hasPermission(req.user, targetPath, 'read')) {
    return res.status(403).json({ error: 'Permission denied' });
  }

  res.download(targetPath);
});

app.get('/api/files/download-folder', authenticateToken, (req, res) => {
  const reqPath = req.query.path;
  if (!reqPath) return res.status(400).json({ error: 'Path is required' });

  const targetPath = resolveAndValidatePath(reqPath);
  if (!targetPath || !fs.existsSync(targetPath) || !fs.statSync(targetPath).isDirectory()) {
    return res.status(404).json({ error: 'Folder not found' });
  }

  if (targetPath === SAKURA_ROOT || targetPath === STORAGE_ROOT) {
    return res.status(403).json({ error: 'Cannot download root directories directly' });
  }

  if (!hasPermission(req.user, targetPath, 'read')) {
    return res.status(403).json({ error: 'Permission denied' });
  }

  const folderName = path.basename(targetPath) || 'folder';
  res.setHeader('Content-Type', 'application/zip');
  res.setHeader('Content-Disposition', `attachment; filename="${encodeURIComponent(folderName)}.zip"`);

  const { ZipArchive } = archiver;
  const archive = new ZipArchive({
    zlib: { level: 5 } // 5 is a good balance between compression speed and ratio
  });

  archive.on('error', (err) => {
    console.error('Archive error:', err);
    if (!res.headersSent) {
      res.status(500).send('Failed to compress folder');
    }
  });

  archive.pipe(res);
  archive.directory(targetPath, false);
  archive.finalize();
});

// Stream endpoint with range request support for media players
app.get('/api/files/stream', authenticateToken, (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', '*');
  const reqPath = req.query.path;
  if (!reqPath) return res.status(400).json({ error: 'Path is required' });

  const targetPath = resolveAndValidatePath(reqPath);
  if (!targetPath || !fs.existsSync(targetPath) || !fs.statSync(targetPath).isFile()) {
    return res.status(404).json({ error: 'File not found' });
  }

  if (!hasPermission(req.user, targetPath, 'read')) {
    return res.status(403).json({ error: 'Permission denied' });
  }

  const stat = fs.statSync(targetPath);
  const fileSize = stat.size;
  const range = req.headers.range;
  const contentType = mime.lookup(targetPath) || 'application/octet-stream';

  let start = 0;
  let end = fileSize - 1;
  let isRange = false;

  if (range) {
    isRange = true;
    const parts = range.replace(/bytes=/, "").split("-");
    const partStart = parts[0];
    const partEnd = parts[1];

    if (partStart === "" && partEnd !== "") {
      // Format: bytes=-num (last num bytes)
      const lastBytes = parseInt(partEnd, 10);
      start = fileSize - lastBytes;
      end = fileSize - 1;
    } else if (partStart !== "" && partEnd === "") {
      // Format: bytes=start-
      start = parseInt(partStart, 10);
      end = fileSize - 1;
    } else if (partStart !== "" && partEnd !== "") {
      // Format: bytes=start-end
      start = parseInt(partStart, 10);
      end = parseInt(partEnd, 10);
    }

    // Safety checks
    if (isNaN(start) || isNaN(end) || start < 0 || end >= fileSize || start > end) {
      res.status(416).send('Requested range not satisfiable\n' + start + '-' + end);
      return;
    }
  }

  if (isRange) {
    const chunksize = (end - start) + 1;
    const file = fs.createReadStream(targetPath, { start, end });
    const head = {
      'Content-Range': `bytes ${start}-${end}/${fileSize}`,
      'Accept-Ranges': 'bytes',
      'Content-Length': chunksize,
      'Content-Type': contentType,
    };

    res.writeHead(206, head);
    file.pipe(res);
  } else {
    const head = {
      'Content-Length': fileSize,
      'Content-Type': contentType,
    };
    res.writeHead(200, head);
    fs.createReadStream(targetPath).pipe(res);
  }
});

app.get('/api/files/media-info', authenticateToken, (req, res) => {
  const reqPath = req.query.path;
  if (!reqPath) return res.status(400).json({ error: 'Path is required' });

  const targetPath = resolveAndValidatePath(reqPath);
  if (!targetPath || !fs.existsSync(targetPath) || !fs.statSync(targetPath).isFile()) {
    return res.status(404).json({ error: 'File not found' });
  }

  if (!hasPermission(req.user, targetPath, 'read')) {
    return res.status(403).json({ error: 'Permission denied' });
  }

  const escapedPath = targetPath.replace(/"/g, '\\"');
  const ffprobeCmd = `ffprobe -v error -show_entries stream=index,codec_type,codec_name:stream_tags=language,title -of json "${escapedPath}"`;

  exec(ffprobeCmd, (err, stdout, stderr) => {
    if (err) {
      console.error('ffprobe error:', err);
      return res.status(500).json({ error: 'Failed to probe file metadata' });
    }

    try {
      const data = JSON.parse(stdout);
      const streams = data.streams || [];
      const audioTracks = [];
      const subtitleTracks = [];

      streams.forEach(s => {
        const trackInfo = {
          index: s.index,
          codec: s.codec_name,
          language: (s.tags && (s.tags.language || s.tags.LANGUAGE)) || 'und',
          title: (s.tags && (s.tags.title || s.tags.TITLE)) || ''
        };

        if (s.codec_type === 'audio') {
          audioTracks.push(trackInfo);
        } else if (s.codec_type === 'subtitle') {
          const textCodecs = ['srt', 'subrip', 'ass', 'ssa', 'mov_text', 'webvtt', 'microdvd'];
          if (textCodecs.includes(s.codec_name) || s.codec_name.includes('text') || s.codec_name.includes('sub')) {
            if (!s.codec_name.includes('pgs') && !s.codec_name.includes('dvd')) {
              subtitleTracks.push(trackInfo);
            }
          }
        }
      });

      res.json({ audio: audioTracks, subtitles: subtitleTracks });
    } catch (parseErr) {
      res.status(500).json({ error: 'Failed to parse media metadata' });
    }
  });
});

app.get('/api/files/subtitle', authenticateToken, (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', '*');
  const reqPath = req.query.path;
  const trackIndex = req.query.track;

  if (!reqPath || !trackIndex) {
    return res.status(400).send('Path and track index are required');
  }

  const targetPath = resolveAndValidatePath(reqPath);
  if (!targetPath || !fs.existsSync(targetPath)) {
    return res.status(404).send('File not found');
  }

  if (!hasPermission(req.user, targetPath, 'read')) {
    return res.status(403).send('Permission denied');
  }

  const escapedPath = targetPath.replace(/"/g, '\\"');
  const trackNum = parseInt(trackIndex, 10);

  res.setHeader('Content-Type', 'text/vtt; charset=utf-8');

  const ffmpegCmd = `ffmpeg -v error -txt_format webvtt -i "${escapedPath}" -map 0:${trackNum} -f webvtt pipe:1`;
  const child = exec(ffmpegCmd);
  child.stdout.pipe(res);

  child.on('error', (err) => {
    console.error('Subtitle extraction error:', err);
    if (!res.headersSent) {
      res.status(500).send('Failed to extract subtitle');
    }
  });
});

app.get('/api/files/stream-audio', authenticateToken, (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', '*');
  const reqPath = req.query.path;
  const trackIndex = req.query.track;
  const ss = req.query.ss || '0';

  if (!reqPath || !trackIndex) {
    return res.status(400).send('Path and track are required');
  }

  const targetPath = resolveAndValidatePath(reqPath);
  if (!targetPath || !fs.existsSync(targetPath)) {
    return res.status(404).send('File not found');
  }

  if (!hasPermission(req.user, targetPath, 'read')) {
    return res.status(403).send('Permission denied');
  }

  const escapedPath = targetPath.replace(/"/g, '\\"');
  const trackNum = parseInt(trackIndex, 10);
  const seekTime = parseFloat(ss);

  res.setHeader('Content-Type', 'audio/mpeg');

  const ffmpegCmd = `ffmpeg -v error -ss ${seekTime} -i "${escapedPath}" -map 0:${trackNum} -c:a libmp3lame -q:a 2 -f mp3 pipe:1`;
  const child = exec(ffmpegCmd);
  child.stdout.pipe(res);

  child.on('error', (err) => {
    console.error('Audio stream extraction error:', err);
    if (!res.headersSent) {
      res.status(500).send('Failed to stream audio');
    }
  });

  req.on('close', () => {
    child.kill('SIGKILL');
  });
});

app.post('/api/files/upload', authenticateToken, (req, res) => {
  upload.single('file')(req, res, function (err) {
    if (err) {
      return res.status(400).json({ error: err.message });
    }
    res.json({ success: true, filename: req.file.originalname });
  });
});

app.post('/api/files/upload-chunk', authenticateToken, (req, res) => {
  uploadChunk.single('file')(req, res, function (err) {
    if (err) {
      return res.status(400).json({ error: err.message });
    }

    const { uploadId, chunkIndex, totalChunks, path: basePath, relativePath, filename } = req.query;
    const total = parseInt(totalChunks, 10);
    const idx = parseInt(chunkIndex, 10);

    const safeUploadId = path.normalize(uploadId).replace(/\\/g, '/').replace(/^\//, '').replace(/\.\.\//g, '');
    const tempDir = path.join(__dirname, 'temp-chunks', safeUploadId);

    fs.readdir(tempDir, (readErr, files) => {
      if (readErr) {
        return res.status(500).json({ error: 'Failed to read chunk directory' });
      }

      if (files.length === total) {
        let targetDir = resolveAndValidatePath(basePath);
        if (!targetDir || !hasPermission(req.user, targetDir, 'write')) {
          fs.rmSync(tempDir, { recursive: true, force: true });
          return res.status(403).json({ error: 'Permission denied or invalid path' });
        }

        if (relativePath) {
          const cleanRelative = path.normalize(relativePath)
            .replace(/\\/g, '/')
            .replace(/^\//, '')
            .replace(/\.\.\//g, '');
          const dirPart = path.dirname(cleanRelative);

          if (dirPart && dirPart !== '.') {
            const fullSubDir = path.join(targetDir, dirPart).replace(/\\/g, '/');
            const validatedSubDir = resolveAndValidatePath(fullSubDir);

            if (!validatedSubDir || !hasPermission(req.user, validatedSubDir, 'write')) {
              fs.rmSync(tempDir, { recursive: true, force: true });
              return res.status(403).json({ error: 'Permission denied or invalid path' });
            }

            fs.mkdirSync(validatedSubDir, { recursive: true });
            targetDir = validatedSubDir;
          }
        }

        const finalPath = path.join(targetDir, filename);
        const writeStream = fs.createWriteStream(finalPath);

        writeStream.on('error', (writeErr) => {
          console.error('Merge file stream error:', writeErr);
          fs.rmSync(tempDir, { recursive: true, force: true });
          if (!res.headersSent) {
            res.status(500).json({ error: 'Failed to write merged file' });
          }
        });

        writeStream.on('finish', () => {
          fs.rmSync(tempDir, { recursive: true, force: true });
          res.json({ success: true, filename });
        });

        for (let i = 0; i < total; i++) {
          const chunkPath = path.join(tempDir, `chunk-${i}`);
          try {
            const chunkBuffer = fs.readFileSync(chunkPath);
            writeStream.write(chunkBuffer);
          } catch (readChunkErr) {
            console.error('Error reading chunk during merge:', readChunkErr);
            writeStream.destroy();
            fs.rmSync(tempDir, { recursive: true, force: true });
            return res.status(500).json({ error: 'Chunk missing or corrupted during merge' });
          }
        }
        writeStream.end();
      } else {
        res.json({ success: true, chunkIndex: idx });
      }
    });
  });
});

app.post('/api/files/mkdir', authenticateToken, (req, res) => {
  const { path: reqPath, name } = req.body;
  if (!reqPath || !name) {
    return res.status(400).json({ error: 'Path and name are required' });
  }

  const parentPath = resolveAndValidatePath(reqPath);
  if (!parentPath) {
    return res.status(400).json({ error: 'Invalid parent path' });
  }

  const newDirPath = path.join(parentPath, name);
  const validatedDirPath = resolveAndValidatePath(newDirPath);

  if (!validatedDirPath) {
    return res.status(400).json({ error: 'Invalid directory name or path' });
  }

  if (!hasPermission(req.user, validatedDirPath, 'write')) {
    return res.status(403).json({ error: 'Permission denied' });
  }

  if (fs.existsSync(validatedDirPath)) {
    return res.status(400).json({ error: 'Directory already exists' });
  }

  try {
    fs.mkdirSync(validatedDirPath);
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: 'Failed to create directory' });
  }
});

app.delete('/api/files/delete', authenticateToken, (req, res) => {
  const reqPath = req.query.path;
  if (!reqPath) return res.status(400).json({ error: 'Path is required' });

  const targetPath = resolveAndValidatePath(reqPath);
  if (!targetPath || !fs.existsSync(targetPath)) {
    return res.status(404).json({ error: 'Path not found' });
  }

  // Prevent deleting root directories
  if (targetPath === SAKURA_ROOT || targetPath === STORAGE_ROOT) {
    return res.status(403).json({ error: 'Cannot delete root directories' });
  }

  if (!hasPermission(req.user, targetPath, 'write')) {
    return res.status(403).json({ error: 'Permission denied' });
  }

  try {
    const stats = fs.statSync(targetPath);
    if (stats.isDirectory()) {
      fs.rmSync(targetPath, { recursive: true, force: true });
    } else {
      fs.unlinkSync(targetPath);
    }
    res.json({ success: true });
  } catch (err) {
    res.status(500).json({ error: 'Failed to delete' });
  }
});


// Serve Single Page Application for all client routes
app.get('*', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

server.listen(PORT, () => {
  console.log(`Media sharing server running on port ${PORT}`);
});
