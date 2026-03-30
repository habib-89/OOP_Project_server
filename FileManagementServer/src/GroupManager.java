import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class GroupManager {

    private static final String GROUPS_FILE = "groups.txt";
    private static final String MEMBERS_FILE = "group_members.txt";
    private static final String GROUPS_BASE_DIR = "server_files/groups";

    public static class GroupInfo {
        public String groupId;
        public String groupName;
        public String owner;

        public GroupInfo(String groupId, String groupName, String owner) {
            this.groupId = groupId;
            this.groupName = groupName;
            this.owner = owner;
        }
    }

    public static String createGroup(String owner, String groupName) {
        if (groupName == null || groupName.trim().isEmpty()) {
            return "ERROR Group name cannot be empty";
        }

        groupName = groupName.trim();

        if (groupName.contains("|") || groupName.contains(",") || groupName.contains(":")) {
            return "ERROR Group name cannot contain | , or :";
        }

        String groupId = UUID.randomUUID().toString();

        try (PrintWriter writer = new PrintWriter(new FileWriter(GROUPS_FILE, true))) {
            writer.println(groupId + "|" + groupName + "|" + owner);
        } catch (IOException e) {
            return "ERROR Could not create group";
        }

        boolean ownerAdded = addMember(groupId, owner);
        if (!ownerAdded) {
            return "ERROR Could not add owner to group";
        }

        File groupFolder = new File(GROUPS_BASE_DIR, groupId);
        groupFolder.mkdirs();

        return "CREATEGROUP_SUCCESS";
    }

    public static boolean addMember(String groupId, String username) {
        if (groupId == null || username == null) return false;
        if (isMember(groupId, username)) return true;

        try (PrintWriter writer = new PrintWriter(new FileWriter(MEMBERS_FILE, true))) {
            writer.println(groupId + "|" + username);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isMember(String groupId, String username) {
        File file = new File(MEMBERS_FILE);
        if (!file.exists()) return false;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|");
                if (parts.length == 2 &&
                        parts[0].equals(groupId) &&
                        parts[1].equals(username)) {
                    return true;
                }
            }
        } catch (IOException e) {
            System.out.println("Error checking group membership: " + e.getMessage());
        }

        return false;
    }

    public static boolean isOwner(String groupId, String username) {
        GroupInfo info = getGroup(groupId);
        return info != null && info.owner.equals(username);
    }

    public static boolean groupExists(String groupId) {
        return getGroup(groupId) != null;
    }

    public static GroupInfo getGroup(String groupId) {
        File file = new File(GROUPS_FILE);
        if (!file.exists()) return null;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|");
                if (parts.length == 3 && parts[0].equals(groupId)) {
                    return new GroupInfo(parts[0], parts[1], parts[2]);
                }
            }
        } catch (IOException e) {
            System.out.println("Error loading group: " + e.getMessage());
        }

        return null;
    }

    public static List<GroupInfo> getUserGroups(String username) {
        List<GroupInfo> groups = new ArrayList<>();

        File file = new File(GROUPS_FILE);
        if (!file.exists()) return groups;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|");
                if (parts.length == 3) {
                    String groupId = parts[0];
                    String groupName = parts[1];
                    String owner = parts[2];

                    if (isMember(groupId, username)) {
                        groups.add(new GroupInfo(groupId, groupName, owner));
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("Error reading groups: " + e.getMessage());
        }

        return groups;
    }
}
