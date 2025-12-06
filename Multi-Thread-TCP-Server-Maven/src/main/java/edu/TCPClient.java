package edu;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;


public class TCPClient {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private boolean running = true;

    public void connect(String serverAddress, int port) throws IOException {
        this.socket = new Socket(serverAddress, port);
        this.out = new PrintWriter(this.socket.getOutputStream(), true);
        this.in = new BufferedReader(new InputStreamReader(this.socket.getInputStream()));
        System.out.println("Connected to server at " + serverAddress + ":" + port);
    }

    public void sendMessage(String message) throws IOException {
        if (this.out != null) {
            this.out.println(message);
        }
    }

    public void disconnect() throws IOException {
        running = false;
        if (this.in != null) {
            this.in.close();
        }

        if (this.out != null) {
            this.out.close();
        }

        if (this.socket != null) {
            this.socket.close();
        }
    }

    // Thread for receiving messages from the server
    private volatile String lastResponse = null;
    private volatile boolean responseReceived = false;
    private Thread receiverThread;
    
    private void startMessageReceiver() {
        receiverThread = new Thread(() -> {
            try {
                String line;
                while (running && (line = in.readLine()) != null) {
                    if (line.startsWith("receivedMessage ")) {
                        // Receive Direct Message - format: receivedMessage sender "message"
                        String[] parts = line.split(" ", 3);
                        if (parts.length >= 3) {
                            String sender = parts[1];
                            String message = parts[2];
                            // Remove quotes if present
                            if (message.startsWith("\"") && message.endsWith("\"")) {
                                message = message.substring(1, message.length() - 1);
                            }
                            System.out.println("\n[메시지 수신] " + sender + ": " + message);
                        } else {
                            System.out.println("\n" + line);
                        }
                        System.out.print("> ");
                    } else {
                        lastResponse = line;
                        responseReceived = true;
                        System.out.println("\n[Server] " + line);
                        if (!line.equals("LOGIN OK") && !line.equals("REGISTER OK") && !line.startsWith("MESSAGE SENT")) {
                            System.out.print("> ");
                        }
                    }
                }
            } catch (IOException e) {
                if (running) {
                    // Do not print error message if normal shutdown
                }
            }
        });
        receiverThread.setDaemon(true);
        receiverThread.start();
    }

    public static void main(String[] args) {
        
        if (args.length < 3) {
            System.err.println("Usage: TCPClient <hostname> <port> <command> [arguments...]");
            System.err.println("Example 1: TCPClient localhost 8080 login username password");
            System.err.println("Example 2: TCPClient localhost 8080 register username password");
            System.err.println("After login/register, you can send messages interactively.");
            return;
        }

        String hostName = args[0];
        int port = Integer.parseInt(args[1]);
        String command = args[2];
        
        String initialCommand;
        
        if (args.length == 5 && (command.equals("login") || command.equals("register"))) {
            // login or register command: command username password
            String username = args[3];
            String password = args[4];
            initialCommand = command + " " + username + " " + password;
        } else {
            System.err.println("Invalid arguments. Use: login <username> <password> or register <username> <password>");
            return;
        }

        TCPClient client = new TCPClient();
        Scanner scanner = new Scanner(System.in);

        try {
            client.connect(hostName, port);
            client.startMessageReceiver(); // Start message receiver thread
            
            // Execute initial command (login or register)
            client.sendMessage(initialCommand);
            
            // Wait for response (maximum 2 seconds)
            int waitCount = 0;
            while (!client.responseReceived && waitCount < 20) {
                Thread.sleep(100);
                waitCount++;
            }
            
            // Check response
            if (client.lastResponse != null) {
                if (client.lastResponse.equals("LOGIN OK") || client.lastResponse.equals("REGISTER OK")) {
                    System.out.println("\n✓ " + (command.equals("login") ? "Login" : "Registration") + " successful!");
                    System.out.println("You can now send messages.");
                    System.out.println("Available commands:");
                    System.out.println("  - sendDirectMessage <receiver> \"<message>\"");
                    System.out.println("  - sendChannelMessage <channel> <message>");
                    System.out.println("  - createTask <task_description>");
                    System.out.println("Example: sendDirectMessage alice \"Hello!\"");
                    System.out.println("Type 'quit' or 'exit' to disconnect.\n");
                    System.out.print("> ");
                } else if (client.lastResponse.equals("LOGIN FAILED")) {
                    System.err.println("\n✗ Login failed. Please check your username and password.");
                    System.err.println("Tip: Make sure you registered first with: register <username> <password>");
                    return;
                } else if (client.lastResponse.startsWith("REGISTER FAILED") || client.lastResponse.startsWith("REGISTER FAIED") || client.lastResponse.startsWith("ERROR")) {
                    System.err.println("\n✗ " + client.lastResponse);
                    return;
                } else {
                    System.err.println("\n✗ Unexpected response: " + client.lastResponse);
                    return;
                }
            } else {
                System.err.println("\n✗ No response from server. Connection may be lost.");
                return;
            }
            
            // Interactive message sending
            String userInput;
            while (client.running && scanner.hasNextLine()) {
                userInput = scanner.nextLine().trim();
                
                if (userInput.isEmpty()) {
                    System.out.print("> ");
                    continue;
                }
                
                if (userInput.equalsIgnoreCase("quit") || userInput.equalsIgnoreCase("exit")) {
                    System.out.println("Disconnecting...");
                    client.running = false;
                    
                    // Close socket to wake up receiver thread's readLine()
                    try {
                        if (client.socket != null && !client.socket.isClosed()) {
                            client.socket.shutdownInput(); // Shutdown input stream
                            client.socket.close();
                        }
                    } catch (IOException e) {
                        // Ignore
                    }
                    
                    // Clean up resources
                    try {
                        client.disconnect();
                    } catch (IOException e) {
                        // Ignore
                    }
                    
                    if (scanner != null) {
                        scanner.close();
                    }
                    
                    // Exit immediately
                    System.exit(0);
                }
                
                // Send command to server - accept server commands
                if (userInput.startsWith("sendDirectMessage") || 
                    userInput.startsWith("sendChannelMessage") || 
                    userInput.startsWith("createTask") ||
                    userInput.startsWith("assignTask") ||
                    userInput.startsWith("viewTasks")) {
                    client.sendMessage(userInput);
                    System.out.print("> ");
                } else {
                    // Debug: check input content
                    System.err.println("[DEBUG] Input received: '" + userInput + "' (length: " + userInput.length() + ")");
                    System.out.println("Unknown command. Available commands:");
                    System.out.println("  - sendDirectMessage <receiver> \"<message>\"");
                    System.out.println("  - sendChannelMessage <channel> <message>");
                    System.out.println("  - createTask <task_description>");
                    System.out.print("> ");
                }
            }
            
        } catch (Exception e) {
            System.err.println("Client error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (client.running) {
                try {
                    client.disconnect();
                } catch (IOException e) {
                    // Ignore
                }
            }
            if (scanner != null) {
                scanner.close();
            }
        }
    }
}