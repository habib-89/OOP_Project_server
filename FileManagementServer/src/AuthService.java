import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class AuthService {

    private static final String USERS_FILE = "users.txt";
    private static HashMap<String, String> users = new HashMap<>();

    static {
        loadUsers();
        if (users.isEmpty()) {
            users.put("admin", "1234");
            users.put("user", "1111");
            saveUsers();
        }
    }

    public static boolean authenticate(String username, String password) {
        if (users.containsKey(username)) {
            return users.get(username).equals(password);
        }
        return false;
    }

    public static boolean registerUser(String username, String password) {
        if (users.containsKey(username)) return false;
        users.put(username, password);
        saveUsers();
        return true;
    }

    public static boolean userExists(String username) {
        return users.containsKey(username);
    }

    public static List<String> getAllUsers() {
        return new ArrayList<>(users.keySet());
    }

    public static boolean deleteUser(String username) {
        if (!users.containsKey(username)) return false;
        if (username.equals("admin")) return false; // protect admin
        users.remove(username);
        saveUsers();
        return true;
    }

    private static void loadUsers() {
        File file = new File(USERS_FILE);
        if (!file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts.length == 2) {
                    users.put(parts[0], parts[1]);
                }
            }
        } catch (IOException e) {
            System.out.println("Error loading users: " + e.getMessage());
        }
    }

    private static void saveUsers() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(USERS_FILE))) {
            for (String username : users.keySet()) {
                writer.println(username + ":" + users.get(username));
            }
        } catch (IOException e) {
            System.out.println("Error saving users: " + e.getMessage());
        }
    }
}