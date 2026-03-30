import java.io.*;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ClientHandler extends Thread {

    private final Socket socket;
    private String loggedInUser = null;
    private static final String BASE_DIR = "server_files";

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    private File getUserRoot() {
        return new File(BASE_DIR + "/" + loggedInUser);
    }

    private File resolvePath(String relativePath) {
        File resolved = new File(getUserRoot(), relativePath);
        if (!resolved.getAbsolutePath().startsWith(getUserRoot().getAbsolutePath())) {
            return null;
        }
        return resolved;
    }

    private File getGroupRoot(String groupId) {
        return new File(BASE_DIR + "/groups/" + groupId);
    }

    private File resolveGroupPath(String groupId, String relativePath) {
        File root = getGroupRoot(groupId);
        File resolved = (relativePath == null || relativePath.trim().isEmpty())
                ? root
                : new File(root, relativePath);

        if (!resolved.getAbsolutePath().startsWith(root.getAbsolutePath())) {
            return null;
        }
        return resolved;
    }

    @Override
    public void run() {
        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            String message;

            while ((message = in.readUTF()) != null) {
                System.out.println("Client [" + loggedInUser + "]: " + message);

                if (message.startsWith("LOGIN")) {
                    String[] parts = message.split(" ");
                    String username = parts[1];
                    String password = parts[2];

                    if (AuthService.authenticate(username, password)) {
                        loggedInUser = username;
                        getUserRoot().mkdirs();
                        out.writeUTF("SUCCESS");
                    } else {
                        out.writeUTF("FAIL");
                    }

                } else if (message.startsWith("REGISTER")) {
                    String[] parts = message.split(" ");
                    String username = parts[1];
                    String password = parts[2];

                    if (AuthService.registerUser(username, password)) {
                        out.writeUTF("REGISTER_SUCCESS");
                        System.out.println("New user registered: " + username);
                    } else {
                        out.writeUTF("REGISTER_FAIL Username already exists");
                    }

                } else if (message.startsWith("LISTUSERS")) {
                    if (loggedInUser == null || !loggedInUser.equals("admin")) {
                        out.writeUTF("ERROR Unauthorized");
                        continue;
                    }

                    List<String> allUsers = AuthService.getAllUsers();
                    StringBuilder sb = new StringBuilder();
                    for (String u : allUsers) {
                        File userFolder = new File(BASE_DIR + "/" + u);
                        long size = getFolderSize(userFolder);
                        sb.append(u).append(":").append(formatSize(size)).append(",");
                    }

                    String result = sb.toString();
                    if (result.endsWith(",")) {
                        result = result.substring(0, result.length() - 1);
                    }
                    out.writeUTF(result.isEmpty() ? "EMPTY" : result);

                } else if (message.startsWith("DELETEUSER")) {
                    if (loggedInUser == null || !loggedInUser.equals("admin")) {
                        out.writeUTF("ERROR Unauthorized");
                        continue;
                    }

                    String targetUser = message.substring(11).trim();

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

                } else if (message.startsWith("VIEWUSERFILES")) {
                    if (loggedInUser == null || !loggedInUser.equals("admin")) {
                        out.writeUTF("ERROR Unauthorized");
                        continue;
                    }

                    String targetUser = message.substring(14).trim();
                    File userFolder = new File(BASE_DIR + "/" + targetUser);

                    if (!userFolder.exists()) {
                        out.writeUTF("EMPTY");
                        continue;
                    }

                    StringBuilder sb = new StringBuilder();
                    File[] files = userFolder.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.getName().equals(".recycle")) continue;
                            if (f.getName().equals("groups")) continue;

                            String size = f.isDirectory() ? "—" : formatSize(f.length());
                            String date = new SimpleDateFormat("dd MMM yyyy HH:mm")
                                    .format(new Date(f.lastModified()));

                            if (f.isDirectory()) {
                                sb.append("DIR:").append(f.getName())
                                        .append(":—:").append(date).append(",");
                            } else {
                                sb.append("FILE:").append(f.getName())
                                        .append(":").append(size)
                                        .append(":").append(date).append(",");
                            }
                        }
                    }

                    String result = sb.toString();
                    if (result.endsWith(",")) {
                        result = result.substring(0, result.length() - 1);
                    }
                    out.writeUTF(result.isEmpty() ? "EMPTY" : result);

                } else if (message.startsWith("LISTBIN")) {
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    File recycleDir = new File(getUserRoot(), ".recycle");
                    if (!recycleDir.exists() || recycleDir.listFiles() == null) {
                        out.writeUTF("EMPTY");
                        continue;
                    }

                    StringBuilder sb = new StringBuilder();
                    File[] files = recycleDir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            sb.append(f.getName()).append(",");
                        }
                    }

                    String result = sb.toString();
                    if (result.endsWith(",")) {
                        result = result.substring(0, result.length() - 1);
                    }
                    out.writeUTF(result.isEmpty() ? "EMPTY" : result);

                } else if (message.startsWith("RESTORE")) {
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    String recycleName = message.substring(8).trim();
                    File recycleDir = new File(getUserRoot(), ".recycle");
                    File recycleFile = new File(recycleDir, recycleName);

                    if (!recycleFile.exists()) {
                        out.writeUTF("ERROR File not found in recycle bin");
                        continue;
                    }

                    String originalPath = recycleName.substring(0,
                            recycleName.lastIndexOf("##")).replace("__", "/");

                    int lastSlash = originalPath.lastIndexOf("/");
                    String dirPart = lastSlash == -1 ? "" : originalPath.substring(0, lastSlash);
                    String fileName = recycleName.substring(recycleName.lastIndexOf("##") + 2);

                    File restoreDir = dirPart.isEmpty() ? getUserRoot() : new File(getUserRoot(), dirPart);
                    restoreDir.mkdirs();
                    File restoreDest = new File(restoreDir, fileName);

                    if (recycleFile.renameTo(restoreDest)) {
                        out.writeUTF("RESTORE_SUCCESS");
                        System.out.println("Restored: " + restoreDest.getPath());
                    } else {
                        out.writeUTF("ERROR Could not restore file");
                    }

                } else if (message.startsWith("PERMANENTDELETE")) {
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    String recycleName = message.substring(16).trim();
                    File recycleDir = new File(getUserRoot(), ".recycle");
                    File recycleFile = new File(recycleDir, recycleName);

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
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    String dirPath = message.length() > 8 ? message.substring(8).trim() : "";
                    File dir = resolvePath(dirPath);

                    if (dir == null || !dir.exists() || !dir.isDirectory()) {
                        out.writeUTF("ERROR Directory not found");
                        continue;
                    }

                    StringBuilder sb = new StringBuilder();
                    File[] contents = dir.listFiles();

                    if (contents != null) {
                        for (File f : contents) {
                            if (f.getName().equals(".recycle")) continue;
                            if (f.getName().equals("groups")) continue;

                            String size = f.isDirectory() ? "—" : formatSize(f.length());
                            String date = new SimpleDateFormat("dd MMM yyyy HH:mm")
                                    .format(new Date(f.lastModified()));

                            if (f.isDirectory()) {
                                sb.append("DIR:").append(f.getName())
                                        .append(":—:").append(date).append(",");
                            } else {
                                sb.append("FILE:").append(f.getName())
                                        .append(":").append(size)
                                        .append(":").append(date).append(",");
                            }
                        }
                    }

                    String result = sb.toString();
                    if (result.endsWith(",")) {
                        result = result.substring(0, result.length() - 1);
                    }
                    out.writeUTF(result.isEmpty() ? "EMPTY" : result);

                } else if (message.startsWith("MKDIR")) {
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    String path = message.substring(6).trim();
                    File dir = resolvePath(path);

                    if (dir == null) {
                        out.writeUTF("ERROR Invalid path");
                        continue;
                    }

                    if (dir.exists()) {
                        out.writeUTF("ERROR Folder already exists");
                    } else if (dir.mkdirs()) {
                        out.writeUTF("MKDIR_SUCCESS");
                    } else {
                        out.writeUTF("ERROR Could not create folder");
                    }

                } else if (message.startsWith("DELETEDIR")) {
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    String path = message.substring(10).trim();
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

                } else if (message.startsWith("RENAMEDIR")) {
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    String content = message.substring(10).trim();
                    String[] paths = content.split("\\|");

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

                } else if (message.startsWith("MOVEFILE")) {
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    String content = message.substring(9).trim();
                    String[] paths = content.split("\\|");

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

                } else if (message.startsWith("UPLOADGROUP")) {
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    String afterCommand = message.substring(12).trim();
                    int lastSpace = afterCommand.lastIndexOf(" ");
                    if (lastSpace == -1) {
                        out.writeUTF("ERROR Invalid command");
                        continue;
                    }

                    String leftPart = afterCommand.substring(0, lastSpace);
                    long fileSize = Long.parseLong(afterCommand.substring(lastSpace + 1).trim());

                    String[] parts = leftPart.split("\\|", 2);
                    if (parts.length < 2) {
                        out.writeUTF("ERROR Invalid command");
                        continue;
                    }

                    String groupId = parts[0].trim();
                    String filePath = parts[1].trim();

                    if (!GroupManager.groupExists(groupId)) {
                        out.writeUTF("ERROR Group not found");
                        continue;
                    }

                    if (!GroupManager.isMember(groupId, loggedInUser)) {
                        out.writeUTF("ERROR Not authorized");
                        continue;
                    }

                    File destFile = resolveGroupPath(groupId, filePath);
                    if (destFile == null) {
                        out.writeUTF("ERROR Invalid path");
                        continue;
                    }

                    destFile.getParentFile().mkdirs();

                    byte[] buffer = new byte[4096];
                    long remaining = fileSize;

                    try (FileOutputStream fos = new FileOutputStream(destFile)) {
                        while (remaining > 0) {
                            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                            fos.write(buffer, 0, read);
                            remaining -= read;
                        }
                    }

                    out.writeUTF("UPLOADGROUP_SUCCESS");
                    System.out.println("Group file saved: " + destFile.getPath());

                } else if (message.startsWith("UPLOAD")) {
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    String afterCommand = message.substring(7);
                    int lastSpace = afterCommand.lastIndexOf(" ");

                    String filePath = afterCommand.substring(0, lastSpace);
                    long fileSize = Long.parseLong(afterCommand.substring(lastSpace + 1).trim());

                    File destFile = resolvePath(filePath);
                    if (destFile == null) {
                        out.writeUTF("ERROR Invalid path");
                        continue;
                    }

                    destFile.getParentFile().mkdirs();

                    byte[] buffer = new byte[4096];
                    long remaining = fileSize;

                    try (FileOutputStream fos = new FileOutputStream(destFile)) {
                        while (remaining > 0) {
                            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                            fos.write(buffer, 0, read);
                            remaining -= read;
                        }
                    }

                    out.writeUTF("UPLOAD_SUCCESS");
                    System.out.println("File saved: " + destFile.getPath());

                } else if (message.startsWith("DOWNLOADGROUP")) {
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    String content = message.substring(14).trim();
                    String[] parts = content.split("\\|", 2);
                    if (parts.length < 2) {
                        out.writeUTF("ERROR Invalid command");
                        continue;
                    }

                    String groupId = parts[0].trim();
                    String filePath = parts[1].trim();

                    if (!GroupManager.groupExists(groupId)) {
                        out.writeUTF("ERROR Group not found");
                        continue;
                    }

                    if (!GroupManager.isMember(groupId, loggedInUser)) {
                        out.writeUTF("ERROR Not authorized");
                        continue;
                    }

                    File file = resolveGroupPath(groupId, filePath);
                    if (file == null || !file.exists() || file.isDirectory()) {
                        out.writeUTF("ERROR File not found");
                        continue;
                    }

                    out.writeUTF("OK " + file.length());

                    byte[] buffer = new byte[4096];
                    try (FileInputStream fis = new FileInputStream(file)) {
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }
                    out.flush();
                    System.out.println("Group file sent: " + file.getPath());

                } else if (message.startsWith("DOWNLOAD")) {
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    String filePath = message.substring(9).trim();
                    File file = resolvePath(filePath);

                    if (file == null || !file.exists()) {
                        out.writeUTF("ERROR File not found");
                        continue;
                    }

                    out.writeUTF("OK " + file.length());

                    byte[] buffer = new byte[4096];
                    try (FileInputStream fis = new FileInputStream(file)) {
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }
                    out.flush();
                    System.out.println("File sent: " + file.getPath());

                } else if (message.startsWith("DELETEGROUPFILE")) {
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    String content = message.substring(15).trim();
                    String[] parts = content.split("\\|", 2);
                    if (parts.length < 2) {
                        out.writeUTF("ERROR Invalid command");
                        continue;
                    }

                    String groupId = parts[0].trim();
                    String filePath = parts[1].trim();

                    if (!GroupManager.groupExists(groupId)) {
                        out.writeUTF("ERROR Group not found");
                        continue;
                    }

                    if (!GroupManager.isMember(groupId, loggedInUser)) {
                        out.writeUTF("ERROR Not authorized");
                        continue;
                    }

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

                } else if (message.startsWith("DELETE")) {
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    String filePath = message.substring(7).trim();
                    File file = resolvePath(filePath);

                    if (file == null || !file.exists()) {
                        out.writeUTF("ERROR File not found");
                        continue;
                    }

                    File recycleDir = new File(getUserRoot(), ".recycle");
                    recycleDir.mkdirs();

                    String recycleName = filePath.replace("/", "__") + "##" + file.getName();
                    File recycleFile = new File(recycleDir, recycleName);

                    if (file.renameTo(recycleFile)) {
                        out.writeUTF("DELETE_SUCCESS");
                        System.out.println("Moved to recycle bin: " + file.getPath());
                    } else {
                        out.writeUTF("ERROR Could not delete file");
                    }

                } else if (message.startsWith("SHARE")) {
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    String content = message.substring(6).trim();
                    String[] parts = content.split("\\|");
                    String targetUser = parts[0].trim();
                    String filePath = parts[1].trim();

                    if (!AuthService.userExists(targetUser)) {
                        out.writeUTF("ERROR User not found");
                        continue;
                    }

                    if (targetUser.equals(loggedInUser)) {
                        out.writeUTF("ERROR Cannot share with yourself");
                        continue;
                    }

                    File file = resolvePath(filePath);
                    if (file == null || !file.exists() || file.isDirectory()) {
                        out.writeUTF("ERROR File not found");
                        continue;
                    }

                    String shareRecord = loggedInUser + "|" + targetUser + "|" + filePath + "|" + file.getName();

                    try (PrintWriter writer = new PrintWriter(new FileWriter("shared_files.txt", true))) {
                        writer.println(shareRecord);
                    }

                    out.writeUTF("SHARE_SUCCESS");
                    System.out.println(loggedInUser + " shared " + filePath + " with " + targetUser);

                } else if (message.startsWith("UNSHARE")) {
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    String content = message.substring(8).trim();
                    String[] parts = content.split("\\|");
                    String targetUser = parts[0].trim();
                    String filePath = parts[1].trim();

                    File sharedFile = new File("shared_files.txt");
                    if (!sharedFile.exists()) {
                        out.writeUTF("ERROR No shares found");
                        continue;
                    }

                    List<String> lines = new ArrayList<>();
                    try (BufferedReader reader = new BufferedReader(new FileReader(sharedFile))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String[] r = line.split("\\|");
                            if (!(r[0].equals(loggedInUser) && r[1].equals(targetUser) && r[2].equals(filePath))) {
                                lines.add(line);
                            }
                        }
                    }

                    try (PrintWriter writer = new PrintWriter(new FileWriter(sharedFile, false))) {
                        for (String line : lines) writer.println(line);
                    }

                    out.writeUTF("UNSHARE_SUCCESS");

                } else if (message.startsWith("LISTSHARED")) {
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    File sharedFile = new File("shared_files.txt");
                    if (!sharedFile.exists()) {
                        out.writeUTF("EMPTY");
                        continue;
                    }

                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new FileReader(sharedFile))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String[] r = line.split("\\|");
                            if (r[1].equals(loggedInUser)) {
                                sb.append(r[0]).append("|").append(r[2]).append("|").append(r[3]).append(",");
                            }
                        }
                    }

                    String result = sb.toString();
                    if (result.endsWith(",")) {
                        result = result.substring(0, result.length() - 1);
                    }
                    out.writeUTF(result.isEmpty() ? "EMPTY" : result);

                } else if (message.startsWith("DOWNLOADSHARED")) {
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    String content = message.substring(15).trim();
                    String[] parts = content.split("\\|");
                    String ownerUsername = parts[0].trim();
                    String filePath = parts[1].trim();

                    File sharedTxt = new File("shared_files.txt");
                    boolean authorized = false;
                    if (sharedTxt.exists()) {
                        try (BufferedReader reader = new BufferedReader(new FileReader(sharedTxt))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                String[] r = line.split("\\|");
                                if (r[0].equals(ownerUsername) && r[1].equals(loggedInUser) && r[2].equals(filePath)) {
                                    authorized = true;
                                    break;
                                }
                            }
                        }
                    }

                    if (!authorized) {
                        out.writeUTF("ERROR Not authorized");
                        continue;
                    }

                    File ownerRoot = new File(BASE_DIR + "/" + ownerUsername);
                    File file = new File(ownerRoot, filePath);

                    if (!file.exists()) {
                        out.writeUTF("ERROR File not found");
                        continue;
                    }

                    out.writeUTF("OK " + file.length());

                    byte[] buffer = new byte[4096];
                    try (FileInputStream fis = new FileInputStream(file)) {
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }
                    out.flush();
                    System.out.println("Shared file sent: " + file.getPath());

                } else if (message.startsWith("CREATEGROUP")) {
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    String groupName = message.substring(12).trim();
                    String result = GroupManager.createGroup(loggedInUser, groupName);
                    out.writeUTF(result);

                } else if (message.startsWith("ADDGROUPMEMBER")) {
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    String content = message.substring(15).trim();
                    String[] parts = content.split("\\|");

                    if (parts.length < 2) {
                        out.writeUTF("ERROR Invalid command");
                        continue;
                    }

                    String groupId = parts[0].trim();
                    String username = parts[1].trim();

                    if (!GroupManager.groupExists(groupId)) {
                        out.writeUTF("ERROR Group not found");
                        continue;
                    }

                    if (!GroupManager.isOwner(groupId, loggedInUser)) {
                        out.writeUTF("ERROR Only group owner can add members");
                        continue;
                    }

                    if (!AuthService.userExists(username)) {
                        out.writeUTF("ERROR User not found");
                        continue;
                    }

                    if (GroupManager.addMember(groupId, username)) {
                        out.writeUTF("ADDGROUPMEMBER_SUCCESS");
                    } else {
                        out.writeUTF("ERROR Could not add member");
                    }

                } else if (message.startsWith("LISTGROUPS")) {
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    List<GroupManager.GroupInfo> groups = GroupManager.getUserGroups(loggedInUser);
                    if (groups.isEmpty()) {
                        out.writeUTF("EMPTY");
                        continue;
                    }

                    StringBuilder sb = new StringBuilder();
                    for (GroupManager.GroupInfo g : groups) {
                        sb.append(g.groupId)
                                .append("|")
                                .append(g.groupName)
                                .append("|")
                                .append(g.owner)
                                .append(",");
                    }

                    String result = sb.toString();
                    if (result.endsWith(",")) {
                        result = result.substring(0, result.length() - 1);
                    }
                    out.writeUTF(result);

                } else if (message.startsWith("LISTGROUPFILES")) {
                    if (loggedInUser == null) {
                        out.writeUTF("ERROR Not logged in");
                        continue;
                    }

                    String content = message.substring(15).trim();
                    String[] parts = content.split("\\|", 2);

                    String groupId = parts[0].trim();
                    String relativePath = parts.length > 1 ? parts[1].trim() : "";

                    if (!GroupManager.groupExists(groupId)) {
                        out.writeUTF("ERROR Group not found");
                        continue;
                    }

                    if (!GroupManager.isMember(groupId, loggedInUser)) {
                        out.writeUTF("ERROR Not authorized");
                        continue;
                    }

                    File dir = resolveGroupPath(groupId, relativePath);

                    if (dir == null || !dir.exists() || !dir.isDirectory()) {
                        out.writeUTF("ERROR Directory not found");
                        continue;
                    }

                    StringBuilder sb = new StringBuilder();
                    File[] contents = dir.listFiles();

                    if (contents != null) {
                        for (File f : contents) {
                            String size = f.isDirectory() ? "—" : formatSize(f.length());
                            String date = new SimpleDateFormat("dd MMM yyyy HH:mm")
                                    .format(new Date(f.lastModified()));

                            if (f.isDirectory()) {
                                sb.append("DIR:").append(f.getName())
                                        .append(":—:").append(date).append(",");
                            } else {
                                sb.append("FILE:").append(f.getName())
                                        .append(":").append(size)
                                        .append(":").append(date).append(",");
                            }
                        }
                    }

                    String result = sb.toString();
                    if (result.endsWith(",")) {
                        result = result.substring(0, result.length() - 1);
                    }
                    out.writeUTF(result.isEmpty() ? "EMPTY" : result);

                } else {
                    out.writeUTF(loggedInUser == null ? "Please login first." : "Unknown command.");
                }
            }

        } catch (Exception e) {
            System.out.println("Client disconnected: " + e.getMessage());
        }
    }

    private boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
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
