# Sakura Media Server 🌸

A premium, lightweight, self-hosted media streaming server and system administration dashboard built with Java Spring Boot, H2 Database, and a modern vanilla frontend interface.

Designed to easily stream media files and manage server resources with friends under granular folder access controls.

---

## 🚀 Features

### 📁 Media Explorer & File Manager
- **Multiple & Batch File Uploads**: Select and upload multiple files simultaneously with real-time overall progress tracking.
- **Upload Cancellation**: Instant `✕` cancel button to cleanly abort active chunk uploads and batch queues.
- **Dynamic View Layouts**: Switch between a visual **Grid View** (cards) and a compact **List View** (table rows).
- **Format Filtering**: Isolate items instantly by category (Folders, Videos, Images, Audio, or Other formats).
- **Sort Controls**: Sort items dynamically by Name (A-Z / Z-A), Size (Smallest / Largest), and Date Modified (Newest / Oldest) with directory pinning.
- **Dynamic Breadcrumbs**: Smooth folder-hierarchy navigation relative to authorized root directories.
- **Folder Downloads**: Pack and download entire directories as ZIP archives on the fly.

### 🎬 Media Playback
- **Responsive Video Player**: Browser-native HTML5 video streaming modal with dynamic portrait/vertical aspect ratio scaling.
- **External Player Casting**: Export standard-compliant M3U playlists to cast media streams directly to external players (like VLC).
- **Image Viewer**: Integrated inline image rendering.

### 🛡️ User & Security Management
- **Token-Based Sessions**: Robust JWT-based authentication system with environment-driven secret key support.
- **Granular Permissions Manager**: Create, list, and modify targeted path access rules (Read and Write) mapped to specific users. Normal user read permissions automatically grant upload capabilities.
- **Modern Login Security**: Password eye-toggle, autofill theme overrides, and session duration toggling ("Remember Me" checkbox).

### 🖥️ Server Management Dashboard (Admin Only)
- **Live System Telemetry**: Visualized memory usage and CPU load history metrics powered by Chart.js.
- **Docker Stack Controller**: Refresh, view, start, and stop Docker containers directly from the web GUI.
- **Systemd Service Manager**: Monitor active system daemons.
- **Process Telemetry**: Searchable task list showing CPU/memory utilization and Process IDs (PIDs).
- **UFW Firewall Controller**: View active rules and configure incoming/outgoing ports.
- **System Scheduler**: View, execute, and configure background Cron jobs.
- **APT Packages Manager**: Search and refresh upgradable server packages with inline status reports.
- **Log Telemetry**: Real-time inspection of System Logs and Audit Logs (user activity streams).
- **Native Storage Telemetry**: Multi-partition storage capacity analysis using native Java File system APIs to prevent resource leaks.

---

## 🛠️ Technology Stack

- **Backend**: Java 21, Spring Boot 3.x, Spring Data JPA, Hibernate, JWT.
- **Database**: H2 Database (File-based local persistence).
- **Frontend**: Standard HTML5, Vanilla CSS (Cyberpunk-styled UI variables), Vanilla Javascript.
- **UI Enhancements**: Lucide Icons, Chart.js.
- **System Integration**: `ffmpeg`, `ffprobe`, `ufw`, `docker`, `systemctl`.

---

## 📂 Project Structure

```
sakura-media-server/
├── pom.xml                     # Maven project configuration
├── db.js / server.js           # Development mocks and environment helpers
├── media-server.service        # Systemd service deployment configuration template
├── public/                     # Static web assets (compiled assets deployment)
│   ├── index.html              # Core SPA interface
│   ├── app.js                  # Interface state controller
│   └── style.css               # Design system rules
└── src/
    └── main/
        ├── java/com/sakuradata/media/
        │   ├── MediaServerApplication.java    # Application entrypoint
        │   ├── config/                        # Interceptors and CORS settings
        │   ├── controller/                    # Core REST controllers (Admin, Auth, Files)
        │   ├── model/                         # JPA entities (Users, Logs, Cron)
        │   ├── repository/                    # Database interface mappers
        │   └── service/                       # Cron scheduling services
        └── resources/
            ├── application.properties         # Server port, file thresholds, DB parameters
            └── public/                        # Embedded web resources (repackaged built classpath)
```

---

## ⚙️ Configuration & Environment Variables

The server can be configured via environment variables or `src/main/resources/application.properties`:

| Environment Variable | Default Value | Description |
| :--- | :--- | :--- |
| `JWT_SECRET` | `sakura-media-server-secret-key-default` | Secret key used for signing authentication JWT tokens |
| `DEFAULT_ADMIN_USER` | `sakura` | Username for initial seed administrator account |
| `DEFAULT_ADMIN_PASS` | `sakura` | Password for initial seed administrator account |
| `DB_USER` | `sa` | H2 Database connection username |
| `DB_PASS` | *(empty)* | H2 Database connection password |

---

## 📥 Installation & Running

1. **Clone the repository**:
   ```bash
   git clone https://github.com/PartnerKiller/Sakura-Media-Server.git
   cd Sakura-Media-Server
   ```

2. **Configure environment settings**:
   Customize storage roots, upload thresholds, and database parameters inside `src/main/resources/application.properties` or export environment variables.

3. **Build the production package**:
   ```bash
   mvn clean package -DskipTests
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

2. Reload systemd configurations, enable auto-start, and fire up the service:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable media-server
   sudo systemctl start media-server
   ```
