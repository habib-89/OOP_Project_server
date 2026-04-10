# ⬡ FileVault — Networked File Management System

A secure, client-server file management application built with **Java Sockets** and **JavaFX**.  
Users connect to a central server and manage their personal files remotely, with features like  
file sharing, media playback, PDF viewing, recycle bin, bookmarks, multi-tab browsing, and an admin panel.

---

## 📁 Project Structure

```
FileVault/
│
├── filemanagementserver/        # Server project (Java)
│   ├── ServerMain.java          # Entry point — starts the server
│   ├── ClientHandler.java       # Handles each client in a separate thread
│   ├── AuthService.java         # User authentication (login/register)
│   ├── GroupManager.java        # Group management
│   ├── users.txt                # Stored user credentials (auto-generated)
│   ├── shared_files.txt         # Shared file records (auto-generated)
│   └── server_files/            # All uploaded files stored here (auto-generated)
│
├── filesystemclientfx/          # Client project (JavaFX)
│   ├── src/main/java/com/example/filesystemclientfx/
│   │   ├── Main.java                  # JavaFX entry point
│   │   ├── DashboardController.java   # Main file manager UI logic
│   │   ├── LoginController.java       # Login screen logic
│   │   ├── RegisterController.java    # Register screen logic
│   │   ├── AdminController.java       # Admin panel logic
│   │   ├── NetworkManager.java        # All server communication
│   │   ├── SessionManager.java        # Saves login session
│   │   ├── BookmarkManager.java       # Bookmark save/load
│   │   └── ...
│   └── src/main/resources/com/example/filesystemclientfx/
│       ├── Login.fxml                 # Login screen UI
│       ├── Dashboard.fxml             # Main dashboard UI
│       ├── Register.fxml              # Register screen UI
│       ├── Admin.fxml                 # Admin panel UI
│       ├── login.css                  # Login screen styles
│       └── dashboard.css              # Dashboard styles
│
└── filesystemclient/            # Simple console test client (optional)
    └── ClientMain.java
```

---

## ✅ Prerequisites

Before running the project, make sure you have:

| Requirement | Version |
|---|---|
| Java JDK | 21 or higher |
| JavaFX SDK | 21.0.6 |
| Apache Maven | 3.8+ |
| IntelliJ IDEA | Any recent version (recommended) |

> **Note:** The client project uses Maven. The server project is a plain Java project.

---

## 🚀 How to Run

### Step 1 — Run the Server

1. Open the **`OOP_Project_server`** project in IntelliJ IDEA.
2. Make sure `ServerMain.java` is set as the run configuration.
3. Click **Run** ▶.
4. You should see:
   ```
   Server started on port 5001...
   ```
5. **Keep the server running** while using the client.

> The server runs on **port 5001** on `localhost` by default.  
> To allow other laptops on the same network to connect, find your machine's local IP using:
> - **Windows:** Open CMD → type `ipconfig` → look for **IPv4 Address**
> - **Mac/Linux:** Open Terminal → type `ifconfig`

---

### Step 2 — Configure the Client

Open **`filesystemclientfx/src/main/java/com/example/filesystemclientfx/NetworkManager.java`**  
and make sure the IP address matches your server machine:

```java
public void connect() throws Exception {
    socket = new Socket("localhost", 5001); // Change "localhost" to server IP if on another machine
    ...
}
```

If running on the **same machine**, keep `localhost`.  
If running on a **different laptop**, replace with the server's IP, e.g. `"192.168.1.105"`.

---

### Step 3 — Run the Client

1. Open the **`filesystemclientfx`** project in IntelliJ IDEA.
2. Make sure Maven dependencies are loaded:
   - Right-click `pom.xml` → **Maven** → **Reload Project**
3. Run **`Main.java`** ▶.
4. The **FileVault login screen** will appear.

---

## 🔑 Default Login Credentials

When the server starts for the first time, two accounts are created automatically:

| Username | Password | Role |
|---|---|---|
| `admin` | `1234` | Administrator |
| `user` | `1111` | Regular User |

You can also register new accounts from the login screen.

---

## 🌟 Features

| Feature | Description |
|---|---|
| 🔐 Login & Register | Secure user authentication with session memory |
| 📁 File Management | Upload, download, rename, move, delete files and folders |
| 🗑 Recycle Bin | Deleted files go to recycle bin — restore or permanently delete |
| 📤 File Sharing | Share files with other users; view shared files in "Shared with Me" |
| 🎬 Video Player | Built-in video player (MP4, AVI, MKV, MOV) inside the dashboard |
| 🖼 Image Viewer | View images (JPG, PNG, GIF, BMP, WEBP) with zoom support |
| 📄 PDF Viewer | View PDF files directly inside the app using WebView |
| 🗂 Multi-Tab Browsing | Open multiple tabs like a browser — each tab is independent |
| ⭐ Bookmarks | Bookmark files/folders per folder level (like Chrome bookmarks bar) |
| ↕ Sorting | Sort files by Name, Size, or Date |
| 🔍 Search | Search files and folders instantly |
| 👑 Admin Panel | Admin can view all users, their storage usage, and delete users |
| 🌙 Theme Toggle | Switch between Dark and Light mode |
| 📊 Progress Bar | Upload and download progress shown in real-time |

---


## 🗂 How Files Are Stored (Server)

All files are stored on the **server machine** under the `server_files/` directory:

```
server_files/
├── admin/          ← admin's files
├── user/           ← user's files
├── Habib/          ← each registered user gets their own folder
└── groups/         ← group shared folders
```

---

## ⚠️ Common Issues

| Problem | Solution |
|---|---|
| `Connection refused` | Make sure the server is running before starting the client |
| `Module not found: org.apache.pdfbox` | Right-click `pom.xml` → Maven → Reload Project |
| Video won't play | Some codecs (MKV, AVI) may not be supported by JavaFX — use the "Open with System Player" option |
| Files not showing | Click the **↻ Refresh** button in the dashboard |
| Can't connect from another laptop | Make sure both laptops are on the **same WiFi/LAN network** and use the correct server IP |

---

## 👥 Team

> Add your team member names here.

Rafsan Rashid ( 2405048 )

## 📌 Notes for the Teacher

- The **server must be started first** before any client can connect.
- All files uploaded by users are stored in the `server_files/` folder on the server machine.
- The `users.txt` file stores registered user credentials.
- The `shared_files.txt` file tracks which files are shared between users.
- The server supports **multiple simultaneous clients** — each client runs in its own thread.
- The project was built using **Java 25** and **JavaFX 21.0.6**.

---

*Built with ❤️ using Java, JavaFX, and Java Sockets.*
