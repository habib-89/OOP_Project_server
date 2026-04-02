import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class AuthService {

    private static final String USERS_FILE = "users.txt";
    private static final Map<String, String> users =
            Collections.synchronizedMap(new HashMap<>());

    static {
        loadUsers();
        if (users.isEmpty()) {
            users.put("admin", hashPassword("1234"));
            saveUsers();
        }
    }

    public static boolean authenticate(String username, String password) {
        if (username == null || password == null) return false;
        if (!users.containsKey(username)) return false;
        return users.get(username).equals(hashPassword(password));
    }

    public static boolean registerUser(String username, String password) {
        if (!isValid(username, password)) return false;
        synchronized (users) {
            if (users.containsKey(username)) return false;
            users.put(username, hashPassword(password));
        }
        saveUsers();
        return true;
    }

    public static boolean userExists(String username) {
        return users.containsKey(username);
    }

    public static List<String> getAllUsers() {
        synchronized (users) {
            return new ArrayList<>(users.keySet());
        }
    }

    public static boolean deleteUser(String username) {
        if (username == null || username.equals("admin")) return false;
        synchronized (users) {
            if (!users.containsKey(username)) return false;
            users.remove(username);
        }
        saveUsers();
        return true;
    }

    private static boolean isValid(String username, String password) {
        return username != null && !username.isEmpty()
                && password != null && password.length() >= 4
                && !username.contains("|")
                && !username.contains(" ");
    }

    private static String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes("UTF-8"));

            StringBuilder hex = new StringBuilder();
            for (byte b : hash)
                hex.append(String.format("%02x", b));

            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static synchronized void loadUsers() {
        File file = new File(USERS_FILE);
        if (!file.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|", 2);
                if (parts.length == 2 && !parts[0].trim().isEmpty()) {
                    users.put(parts[0].trim(), parts[1].trim());
                }
            }
        } catch (IOException e) {
            System.err.println("Error loading users: " + e.getMessage());
        }
    }

    private static synchronized void saveUsers() {
        File tmpFile = new File(USERS_FILE + ".tmp");
        try (PrintWriter writer = new PrintWriter(new FileWriter(tmpFile))) {
            synchronized (users) {
                for (Map.Entry<String, String> entry : users.entrySet()) {
                    writer.println(entry.getKey() + "|" + entry.getValue());
                }
            }
        } catch (IOException e) {
            System.err.println("Error saving users: " + e.getMessage());
            return;
        }
        // Atomic replace
        new File(USERS_FILE).delete();
        tmpFile.renameTo(new File(USERS_FILE));
    }
}