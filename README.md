# Sakura Media Server 🌸

A premium, lightweight, self-hosted media streaming server and system administration dashboard built with Java Spring Boot, H2 Database, and a modern vanilla frontend interface.

Designed to easily stream media files, mount local disks and Google Drive cloud storage, share network folders via Samba, and manage server resources with granular folder access controls.

---

## 🚀 Features

### 📁 Media Explorer & File Manager
- **Multiple & Batch File Uploads**: Select and upload multiple files simultaneously with real-time overall progress tracking.
- **Upload Cancellation**: Instant `✕` cancel button to cleanly abort active chunk uploads and batch queues.
- **Copy & Move Operations with Cancellation Controls**: Real-time batch progress overlay for Copy and Move operations with instantaneous speed metrics, transferred bytes, and file counters via SSE. Includes **Cancel File (Skip Individual)** to skip the current file and continue with remaining items, and **Cancel All** to immediately abort the batch and auto-delete incomplete destination files with zero corruption.
- **Dynamic View Layouts**: Switch between a visual **Grid View** (cards) and a compact **List View** (table rows).
- **Format Filtering**: Isolate items instantly by category (Folders, Videos, Images, Audio, or Other formats).
- **Sort Controls**: Sort items dynamically by Name (A-Z / Z-A), Size (Smallest / Largest), and Date Modified (Newest / Oldest) with directory pinning.
- **Dynamic Breadcrumbs**: Smooth folder-hierarchy navigation relative to authorized root directories.
- **Multi-Root Storage Boundaries**: Authorized file system roots are dynamically allocated per user (e.g., Home root, Storage root, HDD root, Google Drive) with strict boundary traversal checks.
- **Recycle Bin**: Safe deletion with multi-root `.recycle-bin` support, allowing file restoration and bulk trash cleanup.
- **Folder Downloads**: Pack and download entire directories as ZIP archives on the fly.
- **1-Click Direct Download Share Links**: Generate secure, unguessable public direct download links (`/d/{code}`) for files and folders. Recipients download immediately in a single click with zero login, no intermediary landing page, and full HTTP Byte-Range resumable support. Includes active share link management, configurable expirations (1h, 24h, 7d, 30d, or never), and download hit counters.

### 🎬 Media Playback
- **Responsive Video Player**: Browser-native HTML5 video streaming modal with dynamic portrait/vertical aspect ratio scaling.
- **External Player Casting**: Export standard-compliant M3U playlists to cast media streams directly to external players (like VLC).
- **Image Viewer**: Integrated inline image rendering with zoom and pan controls.

### ☁️ Cloud Storage & Google Drive Integration
- **Attach Drive via Google Login (OAuth 2.0)**: Attach and mount Google Drive accounts to the server in seconds using official **Google Sign-In**. Zero command-line configuration required.
- **Interactive OAuth Flow**: 1-click Google authorization modal generates official consent links, relays authorization codes, and exchanges OAuth refresh tokens automatically.
- **Automated Background Mounts**: Automatically creates, enables, and manages production-grade user systemd mount services (`rclone-<name>.service`) with high-performance VFS read-ahead, caching, and `fusermount` auto-cleanup.
- **Multi-Account & Custom Credentials**: Attach multiple Google Drive accounts simultaneously, paste token JSON directly, or configure custom Google Cloud Client ID/Secret.
- **1-Click Media & SMB Integration**: Allocate connected Google Drives directly as Sakura Media Server library roots, and share Google Drive cloud files to Windows/Mac LAN via SMB with a single click.
- **Live Cloud Telemetry**: Real-time display of Google Drive storage usage, available space, mount status, PID, and uptime.

### 🖧 SMB (Samba) Network Shares Management
- **Visual Share Manager**: View, create, edit, and delete Samba network shares directly from the web interface without touching `/etc/samba/smb.conf` manually.
- **1-Click UNC Copy**: Copy ready-to-use Windows UNC paths (`\\192.168.0.10\<Share>`) or macOS/Linux URLs (`smb://192.168.0.10/<Share>`) to the clipboard with one click.
- **Atomic Configuration Safety**: Automatically creates timestamped `.bak` backups of `smb.conf`, verifies syntax with `testparm -s` prior to applying, and hot-reloads the daemon via `smbcontrol all reload-config` without disconnecting active streaming clients.
- **Live Active Client Sessions Monitor**: Real-time tracker powered by `smbstatus` showing connected client IP addresses, SMB dialects (e.g. `SMB3_11`), encryption/signing status, and currently mounted shares.
- **Samba User & Password Controls**: View Samba accounts, set/update user passwords with 1 click (`smbpasswd -a`), and manage share permissions.
- **Daemon Controls**: View daemon PID and uptime, reload configuration, or restart the Samba daemon from the dashboard.

### 💽 Hardware Disks & Storage Management
- **Connected Storage Scanner**: Real-time hardware disk and partition scanner powered by native `lsblk` telemetry.
- **Smart Partition Filtering**: Automatically filters out unusable system-reserved metadata partitions (e.g., 1 MB BIOS boot, 127 MB MSR, LDM metadata, swap) so only genuine storage devices are presented.
- **1-Click Mount & Unmount**: Mount any connected hard drive, SSD, or USB device to any destination path directly from the UI without manual CLI commands.
- **Auto Root Allocation**: Checkbox option to instantly allocate newly mounted drives as active Media Library roots.
- **Storage Roots Table**: View all active storage roots, disk usage progress bars, permission badges, and enable/disable toggles.

### 🛡️ User & Security Management
- **Token-Based Sessions**: Robust JWT-based authentication system with environment-driven secret key support.
- **Custom Profile Pictures**: Upload, update, and delete custom avatar images (JPG, PNG, GIF up to 5MB) with dynamic, auto-generated colorful SVG initials fallback.
- **Admin Avatar Management**: Administrators can upload, change, or remove profile pictures for any user directly from the user management panel.
- **Granular Permissions Manager**: Create, list, and modify targeted path access rules (Read and Write) mapped to specific users. Normal user read permissions automatically grant upload capabilities.
- **Modern Login Security**: Password eye-toggle, autofill theme overrides, and session duration toggling ("Remember Me" checkbox with persistent 365-day sessions).

### 🖥️ Server Management Dashboard (Admin Only)
- **Live System Telemetry**: Visualized memory usage and CPU load history metrics powered by Chart.js.
- **Docker Stack Controller**: Refresh, view, start, and stop Docker containers directly from the web GUI.
- **Systemd Service Manager**: Monitor and manage active system daemons.
- **Process Telemetry**: Searchable task list showing CPU/memory utilization and Process IDs (PIDs).
- **UFW Firewall Controller**: View active rules and configure incoming/outgoing ports.
- **System Scheduler**: View, execute, and configure background Cron jobs.
- **APT Packages Manager**: Search and refresh upgradable server packages with inline status reports.
- **Log Telemetry**: Real-time inspection of System Logs and Audit Logs (user activity streams).

### 🎨 Visual Customization & Themes
- **Dynamic System Themes**: Choose from 10 premium, customized color schemes (e.g., *Cyber Sakura*, *Deep Ocean*, *Midnight Azure*, *Carbon Gray*, *Aura Green*, *Neon Violet*, *Sunset Orange*, *Crimson Red*, *Forest Lagoon*, *Golden Amber*).
- **Presentation Styles**: Instantly transform the web interface with 10 distinctive layout styles (e.g., *Glassmorphism*, *Minimalist*, *Retro Terminal* scanlines, *Vaporwave Dream*, *Cyberpunk*, *Material Design*, *Nebula Space*, *Steel Chrome*, *Nordic Aurora*, *Aero Classic*).
- **Persistent User Profiles**: Custom styles can be selected globally by administrators or customized on a per-user profile basis.
- **Active Indicator Overlays**: Highlight active themes and styles with a modern glowing border and interactive checkmark icon.

---

## 🛠️ Technology Stack

- **Backend**: Java 21, Spring Boot 3.x, Spring Data JPA, Hibernate, JWT.
- **Database**: H2 Database (File-based local persistence).
- **Frontend**: Standard HTML5, Vanilla CSS (Cyberpunk-styled UI variables), Vanilla Javascript.
- **UI Enhancements**: Lucide Icons, Chart.js.
- **System & Storage Integration**: `rclone` (Google Drive OAuth mounts), `samba` (`smbd`, `testparm`, `smbstatus`, `smbcontrol`), `lsblk`, `ffmpeg`, `ffprobe`, `ufw`, `docker`, `systemctl`.

---

## 📂 Project Structure

```
sakura-media-server/
├── pom.xml                     # Maven project configuration
├── db.js / server.js           # Development mocks and environment helpers
├── data/                       # Local storage for application files (e.g., user avatars)
├── media-server.service        # Systemd service deployment configuration template
├── public/                     # Static web assets (compiled assets deployment)
│   ├── index.html              # Core SPA interface
│   ├── app-v4.js               # Frontend application and state controller
│   └── style.css               # Design system rules
└── src/
    └── main/
        ├── java/com/sakuradata/media/
        │   ├── MediaServerApplication.java    # Application entrypoint
        │   ├── config/                        # Interceptors, JWT and CORS settings
        │   ├── controller/                    # Core REST controllers (Admin, Auth, Files, RecycleBin)
        │   ├── model/                         # JPA entities (Users, Logs, Cron, StorageRoot)
        │   ├── repository/                    # Database interface mappers
        │   └── service/                       # Core services:
        │       ├── CloudStorageService.java   # Google Drive OAuth & rclone mount management
        │       ├── SmbService.java            # Samba share & network user management
        │       ├── StorageService.java        # Hardware disk scanning & partition mounting
        │       └── UserActivityService.java   # Activity and audit logging
        └── resources/
            ├── application.properties         # Server port, file thresholds, DB parameters
            └── public/                        # Embedded web resources (repackaged built classpath)
```

---

## ⚙️ Configuration & Environment Variables

The server can be configured via environment variables or `src/main/resources/application.properties`:

| Environment Variable | Default Value | Description |
| :--- | :--- | :--- |
| `SERVER_PORT` | `5000` | Port on which the media server listens |
| `JWT_SECRET` | `sakura-media-server-secret-key-default` | Secret key used for signing authentication JWT tokens |
| `DEFAULT_ADMIN_USER` | `sakura` | Username for initial seed administrator account |
| `DEFAULT_ADMIN_PASS` | `sakura` | Password for initial seed administrator account |
| `DB_USER` | `SAKURA` | H2 Database connection username |
| `DB_PASS` | *(empty)* | H2 Database connection password |

---

## 📥 Installation & Running

1. **Clone the repository**:
   ```bash
   git clone https://github.com/PartnerKiller/Sakura-Media-Server.git
   cd Sakura-Media-Server
   ```

2. **Configure environment settings**:
   Customize upload thresholds and database parameters inside `src/main/resources/application.properties` or export environment variables.

3. **Build the production package**:
   ```bash
   JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn clean package -DskipTests
   ```

4. **Run the server application**:
   ```bash
   java -jar target/media-server-1.0.0.jar
   ```
   The application starts on port `5000` by default.

### 🐳 Systemd Deployment (Service Configuration)
To deploy the application as a background service on Linux:

1. Copy the template service configuration to your system directory:
   ```bash
   sudo cp media-server.service /etc/systemd/system/media-server.service
   ```

2. Reload systemd configurations, enable auto-start, and start the service:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable media-server
   sudo systemctl start media-server
   ```
