import java.io.*;
import java.net.Socket;

/**
 * Simple CLI test client for the FileVault server.
 * Connects via Wi-Fi to the server at 192.168.0.108.
 */
public class ClientMain {

    public static void main(String[] args) {
        // CHANGE: "localhost" is now your specific Wi-Fi IP
        String serverIP = "192.168.0.108";
        int port = 5001;

        try (Socket socket = new Socket(serverIP, port)) {

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            BufferedReader console = new BufferedReader(new InputStreamReader(System.in));

            System.out.println("Connected to server at " + serverIP);
            System.out.print("Username: ");
            String username = console.readLine();
            System.out.print("Password: ");
            String password = console.readLine();

            // Protocol: LOGIN <user> <pass>
            out.writeUTF("LOGIN " + username + " " + password);
            String loginResponse = in.readUTF();
            System.out.println("Login: " + loginResponse);

            if (!loginResponse.equals("SUCCESS")) {
                System.out.println("Login failed. Exiting.");
                return;
            }

            System.out.println("--- Command Menu ---");
            System.out.println("LISTDIR          - View your files");
            System.out.println("MKDIR <name>     - Create folder");
            System.out.println("UPLOAD <path> <size> - Send a file");
            System.out.println("EXIT             - Close connection");
            System.out.println("--------------------");

            String userInput;
            while ((userInput = console.readLine()) != null) {
                if (userInput.equalsIgnoreCase("LOGOUT") || userInput.equalsIgnoreCase("EXIT")) {
                    out.writeUTF("EXIT");
                    System.out.println("Disconnecting.");
                    break;
                }

                if (userInput.trim().isEmpty()) continue;

                // Send command to server
                out.writeUTF(userInput);

                // Wait for and print server response
                String response = in.readUTF();
                System.out.println("Server: " + response);
            }

        } catch (Exception e) {
            System.err.println("Error: Could not connect to " + serverIP);
            System.err.println("Make sure ServerMain is running and Firewall is off.");
            e.printStackTrace();
        }
    }
}