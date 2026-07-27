const fs = require('fs');
const path = require('path');
const bcrypt = require('bcryptjs');

const DB_PATH = path.join(__dirname, 'database.json');

// Atomic write to avoid file corruption
function saveDb(data) {
  const tempPath = DB_PATH + '.tmp';
  fs.writeFileSync(tempPath, JSON.stringify(data, null, 2), 'utf8');
  fs.renameSync(tempPath, DB_PATH);
}

function loadDb() {
  if (!fs.existsSync(DB_PATH)) {
    const adminUser = process.env.ADMIN_USERNAME || 'admin';
    const adminPass = process.env.ADMIN_PASSWORD || 'admin';
    const salt = bcrypt.genSaltSync(10);
    const passwordHash = bcrypt.hashSync(adminPass, salt);
    
    const initialDb = {
      users: [
        {
          id: 1,
          username: adminUser,
          passwordHash: passwordHash,
          role: 'admin' // Owner has full access
        }
      ],
      permissions: [] // admin doesn't need explicit permissions entries, granted by role
    };
    saveDb(initialDb);
    return initialDb;
  }

  try {
    const content = fs.readFileSync(DB_PATH, 'utf8');
    return JSON.parse(content);
  } catch (err) {
    console.error('Error reading database file, returning empty shell:', err);
    return { users: [], permissions: [] };
  }
}

// Ensure database is initialized on load
let dbInstance = loadDb();

function refreshDb() {
  dbInstance = loadDb();
}

module.exports = {
  getUsers: () => {
    refreshDb();
    return dbInstance.users.map(u => ({ id: u.id, username: u.username, role: u.role }));
  },

  getUserByUsername: (username) => {
    refreshDb();
    return dbInstance.users.find(u => u.username.toLowerCase() === username.toLowerCase());
  },

  getUserById: (id) => {
    refreshDb();
    return dbInstance.users.find(u => u.id === id);
  },

  createUser: async (username, password, role = 'user') => {
    refreshDb();
    if (dbInstance.users.some(u => u.username.toLowerCase() === username.toLowerCase())) {
      throw new Error('User already exists');
    }

    const salt = await bcrypt.genSalt(10);
    const passwordHash = await bcrypt.hash(password, salt);

    const nextId = dbInstance.users.length > 0 
      ? Math.max(...dbInstance.users.map(u => u.id)) + 1 
      : 1;

    const newUser = {
      id: nextId,
      username: username,
      passwordHash: passwordHash,
      role: role
    };

    dbInstance.users.push(newUser);
    saveDb(dbInstance);
    return { id: newUser.id, username: newUser.username, role: newUser.role };
  },

  deleteUser: (userId) => {
    refreshDb();
    const id = parseInt(userId);
    if (id === 1) {
      throw new Error('Cannot delete the owner/admin account');
    }

    dbInstance.users = dbInstance.users.filter(u => u.id !== id);
    dbInstance.permissions = dbInstance.permissions.filter(p => p.userId !== id);
    saveDb(dbInstance);
    return true;
  },

  getPermissions: (userId) => {
    refreshDb();
    const id = parseInt(userId);
    return dbInstance.permissions.filter(p => p.userId === id);
  },

  setPermissions: (userId, permissions) => {
    refreshDb();
    const id = parseInt(userId);
    
    // Validate user exists
    if (!dbInstance.users.some(u => u.id === id)) {
      throw new Error('User not found');
    }

    // Filter out existing permissions for this user
    dbInstance.permissions = dbInstance.permissions.filter(p => p.userId !== id);

    // Add new permissions
    // permissions format: Array of { path, read, write }
    const formatted = permissions.map(p => ({
      userId: id,
      path: path.normalize(p.path).replace(/\\/g, '/'), // store normalized posix-like paths
      read: !!p.read,
      write: !!p.write
    }));

    dbInstance.permissions.push(...formatted);
    saveDb(dbInstance);
    return formatted;
  }
};
