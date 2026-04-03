import java.io.*;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClientHandler extends Thread {

    private final Socket socket;
    private String loggedInUser = null;
    private static final String BASE_DIR = "server_files";

    // 500 MB upload limit
    private static final long MAX_UPLOAD_SIZE = 500L * 1024 * 1024;

    // Lock for shared_files.txt concurrent access
    private static final Object SHARED_FILES_LOCK = new Object();
    private static final Map<String, Set<ClientHandler>> GROUP_DISCUSSION_SUBSCRIBERS = new ConcurrentHashMap<>();
    private final Object pushLock = new Object();
    private volatile String subscribedGroupId = null;

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    private File getUserRoot() {
        return new File(BASE_DIR + "/" + loggedInUser);
    }

    // FIX: use getCanonicalPath() instead of getAbsolutePath() to prevent path traversal
    private File resolvePath(String relativePath) {
        try {
            File root = getUserRoot().getCanonicalFile();
            File resolved = new File(root, relativePath).getCanonicalFile();
            if (!resolved.getPath().startsWith(root.getPath())) {
                return null; // Path traversal attempt blocked
            }
            return resolved;
        } catch (IOException e) {
            return null;
        }
    }

    private File getGroupRoot(String groupId) {
        return new File(BASE_DIR + "/groups/" + groupId);
    }

    private File resolveGroupPath(String groupId, String relativePath) {
        try {
            File root = getGroupRoot(groupId).getCanonicalFile();
            File resolved = (relativePath == null || relativePath.trim().isEmpty())
                    ? root
                    : new File(root, relativePath).getCanonicalFile();
            if (!resolved.getPath().startsWith(root.getPath())) {
                return null;
            }
            return resolved;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void run() {
        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            String message;

            while ((message = in.readUTF()) != null) {
                System.out.println("Client [" + loggedInUser + "]: " + message);

                if (message.startsWith("LOGIN ")) {
                    // FIX: split with limit 3 to handle passwords with spaces
                    String[] parts = message.split(" ", 3);
                    if (parts.length < 3) { out.writeUTF("FAIL"); continue; }
                    String username = parts[1];
                    String password = parts[2];

                    if (AuthService.authenticate(username, password)) {
                        loggedInUser = username;
                        getUserRoot().mkdirs();
                        out.writeUTF("SUCCESS");
                    } else {
                        out.writeUTF("FAIL");
                    }

                } else if (message.startsWith("REGISTER ")) {
                    // FIX: split with limit 3
                    String[] parts = message.split(" ", 3);
                    if (parts.length < 3) { out.writeUTF("REGISTER_FAIL Invalid request"); continue; }
                    String username = parts[1];
                    String password = parts[2];

                    if (AuthService.registerUser(username, password)) {
                        out.writeUTF("REGISTER_SUCCESS");
                        System.out.println("New user registered: " + username);
                    } else {
                        out.writeUTF("REGISTER_FAIL Username already exists or invalid");
                    }

                } else if (message.startsWith("LISTUSERS")) {
                    if (!isAdmin(out)) continue;

                    List<String> allUsers = AuthService.getAllUsers();
                    StringBuilder sb = new StringBuilder();
                    for (String u : allUsers) {
                        File userFolder = new File(BASE_DIR + "/" + u);
                        long size = getFolderSize(userFolder);
                        sb.append(u).append(":").append(formatSize(size)).append(",");
                    }
                    String result = sb.toString();
                    if (result.endsWith(",")) result = result.substring(0, result.length() - 1);
                    out.writeUTF(result.isEmpty() ? "EMPTY" : result);

                } else if (message.startsWith("DELETEUSER ")) {
                    if (!isAdmin(out)) continue;

                    String targetUser = message.substring("DELETEUSER ".length()).trim();

                    if (targetUser.equals("admin")) {
                        out.writeUTF("ERROR Cannot delete admin");
                        continue;
                    }

                    if (AuthService.deleteUser(targetUser)) {
                        File userFolder = new File(BASE_DIR + "/" + targetUser);
                        deleteRecursive(userFolder);
                        out.writeUTF("DELETEUSER_SUCCESS");
                        System.out.println("Admin deleted user: " + targetUser);
                    } else {
                        out.writeUTF("ERROR Could not delete user");
                    }

                } else if (message.startsWith("VIEWUSERFILES ")) {
                    if (!isAdmin(out)) continue;

                    String targetUser = message.substring("VIEWUSERFILES ".length()).trim();
                    File userFolder = new File(BASE_DIR + "/" + targetUser);

                    if (!userFolder.exists()) {
                        out.writeUTF("EMPTY");
                        continue;
                    }

                    out.writeUTF(buildFileListResponse(userFolder, true));

                } else if (message.startsWith("LISTBIN")) {
                    if (!isLoggedIn(out)) continue;

                    File recycleDir = new File(getUserRoot(), ".recycle");
                    if (!recycleDir.exists() || recycleDir.listFiles() == null) {
                        out.writeUTF("EMPTY");
                        continue;
                    }

                    StringBuilder sb = new StringBuilder();
                    File[] files = recycleDir.listFiles();
                    if (files != null) {
                        for (File f : files) sb.append(f.getName()).append(",");
                    }
                    String result = sb.toString();
                    if (result.endsWith(",")) result = result.substring(0, result.length() - 1);
                    out.writeUTF(result.isEmpty() ? "EMPTY" : result);

                } else if (message.startsWith("RESTORE ")) {
                    if (!isLoggedIn(out)) continue;

                    String recycleName = message.substring("RESTORE ".length()).trim();
                    File recycleDir = new File(getUserRoot(), ".recycle");
                    File recycleFile = new File(recycleDir, recycleName);

                    if (!recycleFile.exists()) {
                        out.writeUTF("ERROR File not found in recycle bin");
                        continue;
                    }

                    // FIX: validate recycled file is inside recycle dir
                    try {
                        if (!recycleFile.getCanonicalPath().startsWith(recycleDir.getCanonicalPath())) {
                            out.writeUTF("ERROR Invalid path");
                            continue;
                        }
                    } catch (IOException e) {
                        out.writeUTF("ERROR Invalid path");
                        continue;
                    }

                    int sepIdx = recycleName.lastIndexOf("##");
                    if (sepIdx == -1) { out.writeUTF("ERROR Invalid recycle name"); continue; }

                    String originalPath = recycleName.substring(0, sepIdx).replace("__", "/");
                    String fileName = recycleName.substring(sepIdx + 2);
                    int lastSlash = originalPath.lastIndexOf("/");
                    String dirPart = lastSlash == -1 ? "" : originalPath.substring(0, lastSlash);
                    File restoreDir = dirPart.isEmpty() ? getUserRoot() : new File(getUserRoot(), dirPart);
                    restoreDir.mkdirs();
                    File restoreDest = new File(restoreDir, fileName);

                    if (recycleFile.renameTo(restoreDest)) {
                        out.writeUTF("RESTORE_SUCCESS");
                    } else {
                        out.writeUTF("ERROR Could not restore file");
                    }

                } else if (message.startsWith("PERMANENTDELETE ")) {
                    if (!isLoggedIn(out)) continue;

                    String recycleName = message.substring("PERMANENTDELETE ".length()).trim();
                    File recycleDir = new File(getUserRoot(), ".recycle");
                    File recycleFile = new File(recycleDir, recycleName);

                    // Validate it's inside recycle dir
                    try {
                        if (!recycleFile.getCanonicalPath().startsWith(recycleDir.getCanonicalPath())) {
                            out.writeUTF("ERROR Invalid path");
                            continue;
                        }
                    } catch (IOException e) {
                        out.writeUTF("ERROR Invalid path");
                        continue;
                    }

                    if (!recycleFile.exists()) {
                        out.writeUTF("ERROR File not found");
                        continue;
                    }

                    if (recycleFile.delete()) {
                        out.writeUTF("PERMANENTDELETE_SUCCESS");
                    } else {
                        out.writeUTF("ERROR Could not delete");
                    }

                } else if (message.startsWith("LISTDIR")) {
                    if (!isLoggedIn(out)) continue;

                    String dirPath = message.length() > "LISTDIR".length()
                            ? message.substring("LISTDIR".length()).trim() : "";
                    File dir = resolvePath(dirPath);

                    if (dir == null || !dir.exists() || !dir.isDirectory()) {
                        out.writeUTF("ERROR Directory not found");
                        continue;
                    }

                    out.writeUTF(buildFileListResponse(dir, false));

                } else if (message.startsWith("MKDIR ")) {
                    if (!isLoggedIn(out)) continue;

                    String path = message.substring("MKDIR ".length()).trim();
                    File dir = resolvePath(path);

                    if (dir == null) { out.writeUTF("ERROR Invalid path"); continue; }

                    if (dir.exists()) {
                        out.writeUTF("ERROR Folder already exists");
                    } else if (dir.mkdirs()) {
                        out.writeUTF("MKDIR_SUCCESS");
                    } else {
                        out.writeUTF("ERROR Could not create folder");
                    }

                } else if (message.startsWith("DELETEDIR ")) {
                    if (!isLoggedIn(out)) continue;

                    String path = message.substring("DELETEDIR ".length()).trim();
                    File dir = resolvePath(path);

                    if (dir == null || !dir.exists()) {
                        out.writeUTF("ERROR Not found");
                        continue;
                    }

                    if (deleteRecursive(dir)) {
                        out.writeUTF("DELETEDIR_SUCCESS");
                    } else {
                        out.writeUTF("ERROR Could not delete folder");
                    }

                } else if (message.startsWith("RENAMEDIR ")) {
                    if (!isLoggedIn(out)) continue;

                    String content = message.substring("RENAMEDIR ".length()).trim();
                    String[] paths = content.split("\\|", 2);
                    if (paths.length < 2) { out.writeUTF("ERROR Invalid command"); continue; }

                    File oldFile = resolvePath(paths[0].trim());
                    File newFile = resolvePath(paths[1].trim());

                    if (oldFile == null || newFile == null || !oldFile.exists()) {
                        out.writeUTF("ERROR Invalid path");
                        continue;
                    }

                    if (oldFile.renameTo(newFile)) {
                        out.writeUTF("RENAME_SUCCESS");
                    } else {
                        out.writeUTF("ERROR Could not rename");
                    }

                } else if (message.startsWith("MOVEFILE ")) {
                    if (!isLoggedIn(out)) continue;

                    String content = message.substring("MOVEFILE ".length()).trim();
                    String[] paths = content.split("\\|", 2);
                    if (paths.length < 2) { out.writeUTF("ERROR Invalid command"); continue; }

                    File srcFile = resolvePath(paths[0].trim());
                    File destDir = resolvePath(paths[1].trim());

                    if (srcFile == null || destDir == null || !srcFile.exists()) {
                        out.writeUTF("ERROR Invalid path");
                        continue;
                    }

                    if (!destDir.exists()) {
                        out.writeUTF("ERROR Destination folder not found");
                        continue;
                    }

                    File destFile = new File(destDir, srcFile.getName());
                    if (srcFile.renameTo(destFile)) {
                        out.writeUTF("MOVEFILE_SUCCESS");
                    } else {
                        out.writeUTF("ERROR Could not move file");
                    }

                } else if (message.startsWith("UPLOADGROUP ")) {
                    if (!isLoggedIn(out)) continue;

                    String afterCommand = message.substring("UPLOADGROUP ".length()).trim();
                    int lastSpace = afterCommand.lastIndexOf(" ");
                    if (lastSpace == -1) { out.writeUTF("ERROR Invalid command"); continue; }

                    String leftPart = afterCommand.substring(0, lastSpace);
                    long fileSize;
                    try {
                        fileSize = Long.parseLong(afterCommand.substring(lastSpace + 1).trim());
                    } catch (NumberFormatException e) {
                        out.writeUTF("ERROR Invalid file size");
                        continue;
                    }

                    // FIX: enforce upload size limit
                    if (fileSize > MAX_UPLOAD_SIZE) {
                        out.writeUTF("ERROR File too large (max 500MB)");
                        continue;
                    }

                    String[] parts = leftPart.split("\\|", 2);
                    if (parts.length < 2) { out.writeUTF("ERROR Invalid command"); continue; }

                    String groupId = parts[0].trim();
                    String filePath = parts[1].trim();

                    if (!GroupManager.groupExists(groupId)) { out.writeUTF("ERROR Group not found"); continue; }
                    if (!GroupManager.isMember(groupId, loggedInUser)) { out.writeUTF("ERROR Not authorized"); continue; }

                    File destFile = resolveGroupPath(groupId, filePath);
                    if (destFile == null) { out.writeUTF("ERROR Invalid path"); continue; }

                    destFile.getParentFile().mkdirs();
                    receiveFile(in, destFile, fileSize);
                    out.writeUTF("UPLOADGROUP_SUCCESS");
                    System.out.println("Group file saved: " + destFile.getPath());

                } else if (message.startsWith("UPLOAD ")) {
                    if (!isLoggedIn(out)) continue;

                    String afterCommand = message.substring("UPLOAD ".length());
                    int lastSpace = afterCommand.lastIndexOf(" ");
                    if (lastSpace == -1) { out.writeUTF("ERROR Invalid command"); continue; }

                    String filePath = afterCommand.substring(0, lastSpace).trim();
                    long fileSize;
                    try {
                        fileSize = Long.parseLong(afterCommand.substring(lastSpace + 1).trim());
                    } catch (NumberFormatException e) {
                        out.writeUTF("ERROR Invalid file size");
                        continue;
                    }

                    // FIX: enforce upload size limit
                    if (fileSize > MAX_UPLOAD_SIZE) {
                        out.writeUTF("ERROR File too large (max 500MB)");
                        continue;
                    }

                    File destFile = resolvePath(filePath);
                    if (destFile == null) { out.writeUTF("ERROR Invalid path"); continue; }

                    destFile.getParentFile().mkdirs();
                    receiveFile(in, destFile, fileSize);
                    out.writeUTF("UPLOAD_SUCCESS");
                    System.out.println("File saved: " + destFile.getPath());

                } else if (message.startsWith("DOWNLOADGROUP ")) {
                    if (!isLoggedIn(out)) continue;

                    String content = message.substring("DOWNLOADGROUP ".length()).trim();
                    String[] parts = content.split("\\|", 2);
                    if (parts.length < 2) { out.writeUTF("ERROR Invalid command"); continue; }

                    String groupId = parts[0].trim();
                    String filePath = parts[1].trim();

                    if (!GroupManager.groupExists(groupId)) { out.writeUTF("ERROR Group not found"); continue; }
                    if (!GroupManager.isMember(groupId, loggedInUser)) { out.writeUTF("ERROR Not authorized"); continue; }

                    File file = resolveGroupPath(groupId, filePath);
                    if (file == null || !file.exists() || file.isDirectory()) {
                        out.writeUTF("ERROR File not found");
                        continue;
                    }

                    sendFile(out, file);
                    System.out.println("Group file sent: " + file.getPath());

                } else if (message.startsWith("DOWNLOAD ")) {
                    if (!isLoggedIn(out)) continue;

                    String filePath = message.substring("DOWNLOAD ".length()).trim();
                    File file = resolvePath(filePath);

                    if (file == null || !file.exists() || file.isDirectory()) {
                        out.writeUTF("ERROR File not found");
                        continue;
                    }

                    sendFile(out, file);
                    System.out.println("File sent: " + file.getPath());

                } else if (message.startsWith("DELETEGROUPFILE ")) {
                    if (!isLoggedIn(out)) continue;

                    String content = message.substring("DELETEGROUPFILE ".length()).trim();
                    String[] parts = content.split("\\|", 2);
                    if (parts.length < 2) { out.writeUTF("ERROR Invalid command"); continue; }

                    String groupId = parts[0].trim();
                    String filePath = parts[1].trim();

                    if (!GroupManager.groupExists(groupId)) { out.writeUTF("ERROR Group not found"); continue; }
                    if (!GroupManager.isMember(groupId, loggedInUser)) { out.writeUTF("ERROR Not authorized"); continue; }

                    File file = resolveGroupPath(groupId, filePath);
                    if (file == null || !file.exists() || file.isDirectory()) {
                        out.writeUTF("ERROR File not found");
                        continue;
                    }

                    if (file.delete()) {
                        out.writeUTF("DELETEGROUPFILE_SUCCESS");
                    } else {
                        out.writeUTF("ERROR Could not delete file");
                    }

                } else if (message.startsWith("DELETE ")) {
                    if (!isLoggedIn(out)) continue;

                    String filePath = message.substring("DELETE ".length()).trim();
                    File file = resolvePath(filePath);

                    if (file == null || !file.exists() || file.isDirectory()) {
                        out.writeUTF("ERROR File not found");
                        continue;
                    }

                    File recycleDir = new File(getUserRoot(), ".recycle");
                    recycleDir.mkdirs();

                    // FIX: use UUID suffix to prevent recycle bin name collisions
                    String safeBase = filePath.replace("/", "__").replace("\\", "__");
                    String recycleName = safeBase + "##" + file.getName() + "##" +
                            java.util.UUID.randomUUID().toString().substring(0, 8);
                    File recycleFile = new File(recycleDir, recycleName);

                    if (file.renameTo(recycleFile)) {
                        out.writeUTF("DELETE_SUCCESS");
                        System.out.println("Moved to recycle bin: " + file.getPath());
                    } else {
                        out.writeUTF("ERROR Could not delete file");
                    }

                } else if (message.startsWith("SHARE ")) {
                    if (!isLoggedIn(out)) continue;

                    String content = message.substring("SHARE ".length()).trim();
                    String[] parts = content.split("\\|", 2);
                    if (parts.length < 2) { out.writeUTF("ERROR Invalid command"); continue; }

                    String targetUser = parts[0].trim();
                    String filePath = parts[1].trim();

                    if (!AuthService.userExists(targetUser)) { out.writeUTF("ERROR User not found"); continue; }
                    if (targetUser.equals(loggedInUser)) { out.writeUTF("ERROR Cannot share with yourself"); continue; }

                    File file = resolvePath(filePath);
                    if (file == null || !file.exists() || file.isDirectory()) {
                        out.writeUTF("ERROR File not found");
                        continue;
                    }

                    String shareRecord = loggedInUser + "|" + targetUser + "|" +
                            filePath + "|" + file.getName();

                    synchronized (SHARED_FILES_LOCK) {
                        try (PrintWriter writer = new PrintWriter(new FileWriter("shared_files.txt", true))) {
                            writer.println(shareRecord);
                        }
                    }

                    out.writeUTF("SHARE_SUCCESS");
                    System.out.println(loggedInUser + " shared " + filePath + " with " + targetUser);

                } else if (message.startsWith("UNSHARE ")) {
                    if (!isLoggedIn(out)) continue;

                    String content = message.substring("UNSHARE ".length()).trim();
                    String[] parts = content.split("\\|", 2);
                    if (parts.length < 2) { out.writeUTF("ERROR Invalid command"); continue; }

                    String targetUser = parts[0].trim();
                    String filePath = parts[1].trim();

                    synchronized (SHARED_FILES_LOCK) {
                        File sharedFile = new File("shared_files.txt");
                        if (!sharedFile.exists()) { out.writeUTF("ERROR No shares found"); continue; }

                        List<String> lines = new ArrayList<>();
                        try (BufferedReader reader = new BufferedReader(new FileReader(sharedFile))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                String[] r = line.split("\\|", 4);
                                if (!(r.length >= 3 && r[0].equals(loggedInUser) &&
                                        r[1].equals(targetUser) && r[2].equals(filePath))) {
                                    lines.add(line);
                                }
                            }
                        }

                        try (PrintWriter writer = new PrintWriter(new FileWriter(sharedFile, false))) {
                            for (String line : lines) writer.println(line);
                        }
                    }
                    out.writeUTF("UNSHARE_SUCCESS");

                } else if (message.startsWith("LISTSHARED")) {
                    if (!isLoggedIn(out)) continue;

                    File sharedFile = new File("shared_files.txt");
                    if (!sharedFile.exists()) { out.writeUTF("EMPTY"); continue; }

                    StringBuilder sb = new StringBuilder();
                    synchronized (SHARED_FILES_LOCK) {
                        try (BufferedReader reader = new BufferedReader(new FileReader(sharedFile))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                String[] r = line.split("\\|", 4);
                                if (r.length >= 4 && r[1].equals(loggedInUser)) {
                                    sb.append(r[0]).append("|")
                                            .append(r[2]).append("|")
                                            .append(r[3]).append(",");
                                }
                            }
                        }
                    }

                    String result = sb.toString();
                    if (result.endsWith(",")) result = result.substring(0, result.length() - 1);
                    out.writeUTF(result.isEmpty() ? "EMPTY" : result);

                } else if (message.startsWith("DOWNLOADSHARED ")) {
                    if (!isLoggedIn(out)) continue;

                    // FIX: use correct command length
                    String content = message.substring("DOWNLOADSHARED ".length()).trim();
                    String[] parts = content.split("\\|", 2);

                    if (parts.length < 2) { out.writeUTF("ERROR Invalid request"); continue; }

                    String ownerUsername = parts[0].trim();
                    String filePath = parts[1].trim();

                    System.out.println("=== DOWNLOADSHARED ===");
                    System.out.println("Owner: " + ownerUsername);
                    System.out.println("FilePath: " + filePath);
                    System.out.println("RequestedBy: " + loggedInUser);

                    File sharedTxt = new File("shared_files.txt");
                    boolean authorized = false;
                    String authorizedPath = null;

                    synchronized (SHARED_FILES_LOCK) {
                        if (sharedTxt.exists()) {
                            try (BufferedReader reader = new BufferedReader(new FileReader(sharedTxt))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    if (line.trim().isEmpty()) continue;
                                    String[] r = line.split("\\|", 4);
                                    if (r.length < 3) continue;

                                    String storedOwner = r[0].trim();
                                    String storedTarget = r[1].trim();
                                    String storedPath = r[2].trim();

                                    String storedFilename = storedPath.contains("/") ?
                                            storedPath.substring(storedPath.lastIndexOf("/") + 1) : storedPath;
                                    String requestedFilename = filePath.contains("/") ?
                                            filePath.substring(filePath.lastIndexOf("/") + 1) : filePath;

                                    if (storedOwner.equals(ownerUsername) &&
                                            storedTarget.equals(loggedInUser) &&
                                            (storedPath.equals(filePath) || storedFilename.equals(requestedFilename))) {
                                        authorized = true;
                                        authorizedPath = storedPath;
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    if (!authorized) {
                        System.out.println("Not authorized for shared download");
                        out.writeUTF("ERROR Not authorized");
                        continue;
                    }

                    // Resolve file safely within owner's folder
                    File ownerRoot = new File(BASE_DIR + "/" + ownerUsername);
                    File file;
                    try {
                        file = new File(ownerRoot, authorizedPath).getCanonicalFile();
                        if (!file.getPath().startsWith(ownerRoot.getCanonicalPath())) {
                            out.writeUTF("ERROR Invalid path");
                            continue;
                        }
                    } catch (IOException e) {
                        out.writeUTF("ERROR Invalid path");
                        continue;
                    }

                    if (!file.exists() || file.isDirectory()) {
                        out.writeUTF("ERROR File not found");
                        continue;
                    }

                    sendFile(out, file);
                    System.out.println("Shared file sent: " + file.getPath());

                } else if (message.startsWith("CREATEGROUP ")) {
                    if (!isLoggedIn(out)) continue;

                    String groupName = message.substring("CREATEGROUP ".length()).trim();
                    String result = GroupManager.createGroup(loggedInUser, groupName);
                    out.writeUTF(result);

                } else if (message.startsWith("ADDGROUPMEMBER ")) {
                    if (!isLoggedIn(out)) continue;

                    String content = message.substring("ADDGROUPMEMBER ".length()).trim();
                    String[] parts = content.split("\\|", 2);
                    if (parts.length < 2) { out.writeUTF("ERROR Invalid command"); continue; }

                    String groupId = parts[0].trim();
                    String username = parts[1].trim();

                    if (!GroupManager.groupExists(groupId)) { out.writeUTF("ERROR Group not found"); continue; }
                    if (!GroupManager.isOwner(groupId, loggedInUser)) { out.writeUTF("ERROR Only group owner can add members"); continue; }
                    if (!AuthService.userExists(username)) { out.writeUTF("ERROR User not found"); continue; }

                    if (GroupManager.addMember(groupId, username)) {
                        out.writeUTF("ADDGROUPMEMBER_SUCCESS");
                    } else {
                        out.writeUTF("ERROR Could not add member");
                    }

                } else if (message.startsWith("LISTGROUPS")) {
                    if (!isLoggedIn(out)) continue;

                    List<GroupManager.GroupInfo> groups = GroupManager.getUserGroups(loggedInUser);
                    if (groups.isEmpty()) { out.writeUTF("EMPTY"); continue; }

                    StringBuilder sb = new StringBuilder();
                    for (GroupManager.GroupInfo g : groups) {
                        sb.append(g.groupId).append("|")
                                .append(g.groupName).append("|")
                                .append(g.owner).append(",");
                    }
                    String result = sb.toString();
                    if (result.endsWith(",")) result = result.substring(0, result.length() - 1);
                    out.writeUTF(result);

                } else if (message.startsWith("LISTGROUPFILES ")) {
                    if (!isLoggedIn(out)) continue;

                    String content = message.substring("LISTGROUPFILES ".length()).trim();
                    String[] parts = content.split("\\|", 2);

                    String groupId = parts[0].trim();
                    String relativePath = parts.length > 1 ? parts[1].trim() : "";

                    if (!GroupManager.groupExists(groupId)) { out.writeUTF("ERROR Group not found"); continue; }
                    if (!GroupManager.isMember(groupId, loggedInUser)) { out.writeUTF("ERROR Not authorized"); continue; }

                    File dir = resolveGroupPath(groupId, relativePath);
                    if (dir == null || !dir.exists() || !dir.isDirectory()) {
                        out.writeUTF("ERROR Directory not found");
                        continue;
                    }

                    out.writeUTF(buildFileListResponse(dir, false));


                } else if (message.startsWith("SETFILEDESCRIPTION ")) {
                    if (!isLoggedIn(out)) continue;

                    String[] parts = message.substring("SETFILEDESCRIPTION ".length()).split("\\|", 3);
                    if (parts.length < 3) {
                        out.writeUTF("ERROR Invalid command");
                        continue;
                    }

                    String groupId = parts[0].trim();
                    String fileName = parts[1].trim();
                    String description = parts[2].trim();

                    if (!GroupManager.groupExists(groupId)) { out.writeUTF("ERROR Group not found"); continue; }
                    if (!GroupManager.isMember(groupId, loggedInUser)) { out.writeUTF("ERROR Not authorized"); continue; }

                    File metaDir = new File(BASE_DIR + "/groups/" + groupId + "/.meta");
                    metaDir.mkdirs();

                    File metaFile = new File(metaDir, fileName + ".txt");
                    List<String> existingComments = new ArrayList<>();

                    if (metaFile.exists()) {
                        try (BufferedReader reader = new BufferedReader(new FileReader(metaFile))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.startsWith("comment:")) existingComments.add(line);
                            }
                        }
                    }

                    try (PrintWriter writer = new PrintWriter(new FileWriter(metaFile, false))) {
                        writer.println("description:" + description);
                        for (String c : existingComments) writer.println(c);
                    }

                    out.writeUTF("SETFILEDESCRIPTION_SUCCESS");
                    broadcastGroupDiscussionUpdate(groupId, fileName);

                } else if (message.startsWith("ADDCOMMENT ")) {
                    if (!isLoggedIn(out)) continue;

                    String[] parts = message.substring("ADDCOMMENT ".length()).split("\\|", 3);
                    if (parts.length < 3) {
                        out.writeUTF("ERROR Invalid command");
                        continue;
                    }

                    String groupId = parts[0].trim();
                    String fileName = parts[1].trim();
                    String comment = parts[2].trim();

                    if (!GroupManager.groupExists(groupId)) { out.writeUTF("ERROR Group not found"); continue; }
                    if (!GroupManager.isMember(groupId, loggedInUser)) { out.writeUTF("ERROR Not authorized"); continue; }

                    File metaDir = new File(BASE_DIR + "/groups/" + groupId + "/.meta");
                    metaDir.mkdirs();

                    File metaFile = new File(metaDir, fileName + ".txt");
                    if (!metaFile.exists()) {
                        try (PrintWriter writer = new PrintWriter(new FileWriter(metaFile))) {
                            writer.println("description:");
                        }
                    }

                    try (PrintWriter writer = new PrintWriter(new FileWriter(metaFile, true))) {
                        writer.println("comment:" + loggedInUser + "|" + comment);
                    }

                    out.writeUTF("COMMENT_SUCCESS");
                    broadcastGroupDiscussionUpdate(groupId, fileName);

                } else if (message.startsWith("GETFILEDISCUSSION ")) {
                    if (!isLoggedIn(out)) continue;

                    String[] parts = message.substring("GETFILEDISCUSSION ".length()).split("\\|", 2);
                    if (parts.length < 2) {
                        out.writeUTF("ERROR Invalid command");
                        continue;
                    }

                    String groupId = parts[0].trim();
                    String fileName = parts[1].trim();

                    if (!GroupManager.groupExists(groupId)) { out.writeUTF("ERROR Group not found"); continue; }
                    if (!GroupManager.isMember(groupId, loggedInUser)) { out.writeUTF("ERROR Not authorized"); continue; }

                    File metaFile = new File(BASE_DIR + "/groups/" + groupId + "/.meta/" + fileName + ".txt");
                    if (!metaFile.exists()) {
                        out.writeUTF("EMPTY");
                        continue;
                    }

                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new FileReader(metaFile))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line).append(";;");
                        }
                    }

                    String result = sb.toString();
                    if (result.endsWith(";;")) result = result.substring(0, result.length() - 2);
                    out.writeUTF(result.isEmpty() ? "EMPTY" : result);



                } else if (message.startsWith("SUBSCRIBE_GROUP_DISCUSSION ")) {
                    if (!isLoggedIn(out)) continue;

                    String groupId = message.substring("SUBSCRIBE_GROUP_DISCUSSION ".length()).trim();

                    if (!GroupManager.groupExists(groupId)) {
                        out.writeUTF("ERROR Group not found");
                        continue;
                    }
                    if (!GroupManager.isMember(groupId, loggedInUser)) {
                        out.writeUTF("ERROR Not authorized");
                        continue;
                    }

                    subscribeGroupDiscussion(groupId);
                    out.writeUTF("SUBSCRIBE_SUCCESS");

                } else if (message.equals("UNSUBSCRIBE_GROUP_DISCUSSION")) {
                    if (!isLoggedIn(out)) continue;
                    unsubscribeGroupDiscussion();
                    out.writeUTF("UNSUBSCRIBE_SUCCESS");

                } else if (message.startsWith("UPLOAD_PROFILE ")) {
                    if (!isLoggedIn(out)) continue;

                    long fileSize;
                    try {
                        fileSize = Long.parseLong(message.split(" ")[1]);
                    } catch (Exception e) {
                        out.writeUTF("ERROR Invalid size");
                        continue;
                    }

                    if (fileSize > 5 * 1024 * 1024) {
                        out.writeUTF("ERROR Profile too large");
                        continue;
                    }

                    File dir = new File(BASE_DIR + "/profile_pics");
                    dir.mkdirs();

                    File file = new File(dir, loggedInUser + ".png");

                    receiveFile(in, file, fileSize);

                    out.writeUTF("UPLOAD_PROFILE_SUCCESS");

                    System.out.println("Profile uploaded for: " + loggedInUser);

                } else if (message.startsWith("DOWNLOAD_PROFILE")) {
                    if (!isLoggedIn(out)) continue;

                    File file = new File(BASE_DIR + "/profile_pics/" + loggedInUser + ".png");
                    if (!file.exists() || file.isDirectory()) {
                        out.writeUTF("ERROR No profile picture");
                        continue;
                    }

                    sendFile(out, file);

                } else {
                    out.writeUTF(loggedInUser == null ? "Please login first." : "Unknown command.");
                }
            }

        } catch (Exception e) {
            System.out.println("Client disconnected: " + e.getMessage());
        } finally {
            unsubscribeGroupDiscussion();
            try { socket.close(); } catch (IOException ignored) {}
        }
    }


    private void subscribeGroupDiscussion(String groupId) {
        unsubscribeGroupDiscussion();
        GROUP_DISCUSSION_SUBSCRIBERS
                .computeIfAbsent(groupId, g -> ConcurrentHashMap.newKeySet())
                .add(this);
        subscribedGroupId = groupId;
    }

    private void unsubscribeGroupDiscussion() {
        if (subscribedGroupId == null) return;

        Set<ClientHandler> listeners = GROUP_DISCUSSION_SUBSCRIBERS.get(subscribedGroupId);
        if (listeners != null) {
            listeners.remove(this);
            if (listeners.isEmpty()) {
                GROUP_DISCUSSION_SUBSCRIBERS.remove(subscribedGroupId);
            }
        }
        subscribedGroupId = null;
    }

    private void broadcastGroupDiscussionUpdate(String groupId, String fileName) {
        Set<ClientHandler> listeners = GROUP_DISCUSSION_SUBSCRIBERS.get(groupId);
        if (listeners == null || listeners.isEmpty()) return;

        for (ClientHandler handler : new ArrayList<>(listeners)) {
            handler.pushMessage("GROUP_DISCUSSION_UPDATE " + groupId + "|" + fileName);
        }
    }

    private void pushMessage(String message) {
        try {
            synchronized (pushLock) {
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.writeUTF(message);
                out.flush();
            }
        } catch (IOException e) {
            unsubscribeGroupDiscussion();
        }
    }

    // ===== HELPERS =====

    private boolean isLoggedIn(DataOutputStream out) throws IOException {
        if (loggedInUser == null) {
            out.writeUTF("ERROR Not logged in");
            return false;
        }
        return true;
    }

    private boolean isAdmin(DataOutputStream out) throws IOException {
        if (loggedInUser == null || !loggedInUser.equals("admin")) {
            out.writeUTF("ERROR Unauthorized");
            return false;
        }
        return true;
    }

    private void receiveFile(DataInputStream in, File destFile, long fileSize) throws IOException {
        byte[] buffer = new byte[4096];
        long remaining = fileSize;
        try (FileOutputStream fos = new FileOutputStream(destFile)) {
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read == -1) break;
                fos.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private void sendFile(DataOutputStream out, File file) throws IOException {
        out.writeUTF("OK " + file.length());
        byte[] buffer = new byte[4096];
        try (FileInputStream fis = new FileInputStream(file)) {
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
        out.flush();
    }

    private String buildFileListResponse(File dir, boolean skipSpecial) {
        StringBuilder sb = new StringBuilder();
        File[] contents = dir.listFiles();
        if (contents != null) {
            for (File f : contents) {
                if (skipSpecial && (f.getName().equals(".recycle") || f.getName().equals("groups"))) continue;
                String date = new SimpleDateFormat("dd MMM yyyy HH:mm")
                        .format(new Date(f.lastModified()));
                if (f.isDirectory()) {
                    long folderSize = getFolderSize(f);
                    sb.append("DIR:").append(f.getName())
                            .append(":").append(formatSize(folderSize))
                            .append(":").append(date).append(",");
                } else {
                    sb.append("FILE:").append(f.getName())
                            .append(":").append(formatSize(f.length()))
                            .append(":").append(date).append(",");
                }
            }
        }
        String result = sb.toString();
        if (result.endsWith(",")) result = result.substring(0, result.length() - 1);
        return result.isEmpty() ? "EMPTY" : result;
    }

    private boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        return file.delete();
    }

    private long getFolderSize(File folder) {
        long size = 0;
        if (!folder.exists()) return 0;
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) size += getFolderSize(f);
                else size += f.length();
            }
        }
        return size;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        else if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        else if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        else return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}