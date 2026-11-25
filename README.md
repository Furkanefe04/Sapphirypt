<div align="center">
  <img src="src/main/resources/com/sapphircrypt/sapphirypt/icons/sapphirypt-logo-256.png" alt="Sapphirypt Logo" width="128" />
  <h1>Sapphirypt</h1>
  <p><strong>Local-first, offline encrypted file vault desktop application built with Java 22 and JavaFX 22.</strong></p>

  <p>
    <img src="https://img.shields.io/badge/Java-22-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 22" />
    <img src="https://img.shields.io/badge/JavaFX-22.0.1-2563EB?style=for-the-badge&logo=openjdk&logoColor=white" alt="JavaFX 22.0.1" />
    <img src="https://img.shields.io/badge/Gradle-8.11-02303A?style=for-the-badge&logo=gradle&logoColor=white" alt="Gradle 8.11" />
    <img src="https://img.shields.io/badge/Bouncy_Castle-1.87-1E3A8A?style=for-the-badge" alt="Bouncy Castle 1.87" />
    <img src="https://img.shields.io/badge/AES--256--GCM_%7C_Argon2id-0F766E?style=for-the-badge&logo=letsencrypt&logoColor=white" alt="AES-256-GCM and Argon2id" />
    <img src="https://img.shields.io/badge/Windows_%7C_Linux-334155?style=for-the-badge&logo=linux&logoColor=white" alt="Windows and Linux" />
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-3B82F6?style=for-the-badge&logo=apache&logoColor=white" alt="License: Apache 2.0" /></a>
  </p>

  <p>
    <a href="https://github.com/Furkanefe04/Sapphirypt/actions/workflows/release-packages.yml"><img src="https://github.com/Furkanefe04/Sapphirypt/actions/workflows/release-packages.yml/badge.svg" alt="Release Packages CI" /></a>
  </p>
</div>

Sapphirypt stores your files, folders, encrypted notes, and version history inside a single crash-safe `.sapphirypt` container file using authenticated encryption—with zero cloud dependency, zero telemetry, and a self-contained native runtime for end users.

---

## Key Features

### Cryptography & Container Architecture
- **Authenticated Encryption:** Encrypts blocks, metadata, and commit records with **AES-256-GCM** (96-bit nonces, 128-bit authentication tags) and per-record AAD binding.
- **Key Derivation & Separation:** Derives wrapping keys via **Argon2id** (Bouncy Castle 1.87) and separates the unlocked 256-bit DEK into purpose-bound subkeys (`data`, `metadata`, `commit`, `nonce-state`) using **HKDF-SHA-256**.
- **Dual A/B Key & Nonce Slots:** Supports both a **Password** and a **Recovery Key (`.key`)** with dual A/B slot durability, password rotation, recovery-key unlock, and leased monotonic nonces.
- **Crash-Safe Append-Only Container:** Stores 1 MiB data chunks alongside atomic `COMMIT` records inside a single `.sapphirypt` file, supporting crash recovery and automatic or manual compaction to reclaim unused space.
- **Memory & Temp Hardening:** Explicitly wipes sensitive byte/char arrays (`SensitiveBytes`) and isolates external-app previews in a 3-level CSPRNG-named temp hierarchy with owner-only permissions, disabled content indexing, automatic live sync-back on save, and zero-fill cleanup on lock or exit.

### Security & System Integration
- **Single-Instance Lock & IPC:** Enforces one running desktop instance per user profile (`~/.sapphirypt/instance.lock`) and forwards window-focus or `.sapphirypt` file-open requests over an authenticated loopback socket (`instance.port`).
- **Clipboard Guard:** Optional auto-clearing of sensitive clipboard text after a configurable timeout (`15`, `30`, `60`, or `120` seconds) and automatically on vault lock—removing payloads only if the clipboard still matches the tracked copy.
- **OS System Tray:** Tray menu controls (**Show**, **Lock Vault**, **Exit**) with optional minimize-to-tray when closing the main window.
- **Vault Lifecycle Safeguards:** Configurable inactivity auto-lock, optional auto-compaction on lock, and sleep/lock hold during large file transfers.
- **Live Localization & Themes:** Instant **English / Turkish** UI language switching and **Dark / Light** theme toggling persisted in `~/.sapphirypt/settings.properties`.

### File Management & Usability
- **Grid & List Views with Sorting:** Sort entries by **Name**, **Size**, **Date**, or **Type** in Ascending or Descending order.
- **Favorites & Custom Tags:** Star favorite entries and assign custom tags (up to 20 per entry) with quick filtering, stored in backward-compatible metadata schema v2.
- **Multi-Selection:** Rubber-band lasso rectangle selection in Grid view, `Shift+Click` range selection, and `Ctrl+A` select-all.
- **Drag-and-Drop Import & Drag-Out Export:** Drag files/folders into the vault or drag encrypted items (`<= 64 MiB`) out to your desktop/file manager via hardened temporary staging.
- **Optional Source Shredding:** Can securely overwrite (random + zero passes), rename, and delete imported source files without following symbolic links (disabled by default).
- **Version History:** Automatically archives up to **10** previous versions on overwrite or external-edit sync-back, accessible via the **Version Notes →** button and slide-over side drawer with **Restore**, **Export**, **Delete**, and **Clear All** controls plus full compaction block-offset preservation.

### Media, Built-in Previews & Editor
- **Async Grid Thumbnails:** In-memory LRU thumbnail cache (up to 120 images) for fast Grid browsing without touching disk.
- **Built-in Encrypted Text & Note Editor:** Create new encrypted notes (**New Note...**) or edit text/code files directly in the Inspector (`Ctrl+S`, **Save**, **Revert**)—encrypted completely in memory with automatic version archiving.
- **Image Viewer & Lightbox:** Zoom, 90° rotation, and fullscreen **Lightbox** inspection for images.
- **In-Memory PDF, Audio & Video Previews:** Pure-Java in-memory PDF metadata, page count, text, and embedded image extraction, plus built-in Audio (`mp3`, `wav`, `aac`, `m4a`, `aif`, `aiff`) and Video (`mp4`, `m4v`) playback controls via `javafx.media`.
- **Extended Image Formats:** Supports standard raster formats (`png`, `jpg`, `jpeg`, `jfif`, `gif`, `bmp`, `tif`, `tiff`), vector/Web images (`svg`, `webp` via `javafx.web`), and embedded preview extraction for `heic`/`heif`.

---

## Requirements (Running from Source)

- **JDK 22** (e.g., Amazon Corretto 22 or Eclipse Temurin 22)
- **Internet connection on first build/run:** The included Gradle Wrapper (`gradlew` / `gradlew.bat`) automatically downloads Gradle, **JavaFX 22.0.1** (`controls`, `fxml`, `media`, `web`), and **Bouncy Castle 1.87**. No separate Gradle installation is required.
- **Linux desktop note:** Running the UI from source or portable builds on Linux requires a desktop session (X11/Wayland), GTK 3, and GStreamer libraries for `javafx.media` audio/video playback.

---

## Quick Start

Clone the repository and launch with the Gradle Wrapper:

```bash
git clone https://github.com/Furkanefe04/Sapphirypt.git
cd Sapphirypt
```

### Windows (PowerShell)

```powershell
# Optional if your default JAVA_HOME points to another JDK version:
$env:JAVA_HOME = "C:\Path\To\jdk-22"

# Run the application
.\gradlew.bat run

# Run the test suite
.\gradlew.bat test
```

### Linux / macOS

```bash
chmod +x gradlew

# Run the application
./gradlew run

# Run the test suite
./gradlew test
```

---

## Packaging & Native Distributions

Sapphirypt uses `jpackage` (and `jlink`) to bundle a minimal, self-contained Java and JavaFX runtime inside native packages—**end users do not need Java installed**.

Because `jpackage` builds native packages only for the current host OS (it does not cross-compile), run Windows tasks on Windows and Linux tasks on Linux (or WSL2).

| Platform | Gradle Task | Output Location | Requirements |
| :--- | :--- | :--- | :--- |
| **Windows** | `packageWindowsExe` | `build/distributions/windows/Sapphirypt/` | JDK 22 on Windows (portable folder with `Sapphirypt.exe`, `app/`, `runtime/`) |
| **Windows** | `packageWindowsInstaller` | `build/distributions/windows-installer/` | JDK 22 + **WiX Toolset 3.x** (single `.exe` installer with `.sapphirypt` association) |
| **Windows** | `packageWindowsMsi` | `build/distributions/windows-msi/` | JDK 22 + **WiX Toolset 3.x** (`.msi` installer) |
| **Windows** | `packageWindowsAll` | All Windows outputs above | JDK 22 + **WiX Toolset 3.x** |
| **Linux** | `packageLinuxApp` | `build/distributions/linux/Sapphirypt/` | JDK 22 on Linux/WSL |
| **Linux** | `packageLinuxDeb` | `build/distributions/linux-deb/` | JDK 22 + `dpkg-deb` / `fakeroot` |
| **Linux** | `packageLinuxRpm` | `build/distributions/linux-rpm/` | JDK 22 + `rpmbuild` |
| **Linux** | `packageLinux` | `linux/` bundle + `linux-deb/` | JDK 22 + `dpkg-deb` / `fakeroot` |
| **Any (Host OS)** | `jlinkZip` | `build/distributions/app-<platform>.zip` | JDK 22 (minimal runtime ZIP for host OS) |
| **Any** | `sha256Checksums` | `.sha256` files alongside artifacts | Built distribution outputs |

### Automated CI Builds (Windows & Linux)

The [Release Packages workflow](https://github.com/Furkanefe04/Sapphirypt/actions/workflows/release-packages.yml) ([`.github/workflows/release-packages.yml`](.github/workflows/release-packages.yml)) builds tested Windows (`.exe` installer, `.msi`, portable `.zip`) and Linux (`.deb`, `.rpm`, portable `.tar.gz`) packages along with `.sha256` checksums automatically on `v*` tag pushes or manual `workflow_dispatch` runs.

---

## Project Structure

```text
Sapphirypt/
├── .github/workflows/          # Release packaging CI for Windows & Linux
├── packaging/                  # .sapphirypt file association properties
├── src/main/java/com/sapphircrypt/sapphirypt/
│   ├── SapphiryptApplication.java  # App entry point, single-instance & stage lifecycle
│   ├── crypto/                 # AES-256-GCM, Argon2id, HKDF-SHA-256, Key/Recovery & memory wiping
│   ├── format/                 # Binary header, key slots, nonce state, record frames, metadata v1/v2
│   ├── storage/                # VaultSession, parallel worker pool, BlockCache, TempPreview, Shredder, IPC
│   └── ui/                     # JavaFX views, Inspector, Lightbox, PDF/media previews, Tray, ClipboardGuard, i18n
├── src/main/resources/         # Stylesheets (CSS) and application icons
├── src/test/java/              # JUnit 5 unit and integration tests
├── build.gradle.kts            # Toolchain, JavaFX, jlink, jpackage, and checksum tasks
└── LICENSE                     # Apache License 2.0
```

---

## License

This project is licensed under the [Apache License 2.0](LICENSE).
