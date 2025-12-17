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
                            System.out.println("\n[Message Received] " + sender + ": " + message);
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
        if (args.length < 2) {
            System.err.println("Usage: TCPClient <hostname> <port>");
            System.err.println("Example: TCPClient localhost 8080");
            System.err.println("After connecting, type commands like:");
            System.err.println("  login <username> <password>");
            System.err.println("  register <username> <password>");
            return;
        }

        String hostName = args[0];
        int port = Integer.parseInt(args[1]);

        TCPClient client = new TCPClient();
        Scanner scanner = new Scanner(System.in);

        try {
            client.connect(hostName, port);
            client.startMessageReceiver();
            
            System.out.println("Connected! Type commands:");
            System.out.println("  login <username> <password>");
            System.out.println("  register <username> <password>");
            System.out.println("Type 'quit' or 'exit' to disconnect.\n");
            System.out.print("> ");
            
            // Interactive command input
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
                    
                    try {
                        if (client.socket != null && !client.socket.isClosed()) {
                            client.socket.shutdownInput();
                            client.socket.close();
                        }
                    } catch (IOException e) {
                        // Ignore
                    }
                    
                    try {
                        client.disconnect();
                    } catch (IOException e) {
                        // Ignore
                    }
                    
                    if (scanner != null) {
                        scanner.close();
                    }
                    
                    System.exit(0);
                }
                
                // Handle login/register commands
                if (userInput.startsWith("login ") || userInput.startsWith("register ")) {
                    client.sendMessage(userInput);
                    client.responseReceived = false;
                    client.lastResponse = null;
                    
                    // Wait for response
                    int waitCount = 0;
                    while (!client.responseReceived && waitCount < 20) {
                        try {
                            Thread.sleep(100);
                            waitCount++;
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    
                    // Check response
                        if (client.lastResponse != null) {
                            if (client.lastResponse.equals("LOGIN OK") || client.lastResponse.equals("REGISTER OK")) {
                                System.out.println("\n✓ " + (userInput.startsWith("login") ? "Login" : "Registration") + " successful!");
                                System.out.println("Available commands:");
                                System.out.println("  send #<channel> <message>  (e.g., send #general Hello)");
                                System.out.println("  send @<username> <message>  (e.g., send @alice Hello)");
                                System.out.println("  createTask <task_description>");
                                System.out.println("  sendFile user <receiver> <file_name>");
                                System.out.print("\n> ");
                        } else if (client.lastResponse.equals("LOGIN FAILED")) {
                            System.err.println("\n✗ Login failed. Check username/password or register first.");
                            System.out.print("> ");
                        } else {
                            System.err.println("\n✗ " + client.lastResponse);
                            System.out.print("> ");
                        }
                    } else {
                        System.err.println("\n✗ No response from server.");
                        System.out.print("> ");
                    }
                    continue;
                }
                
                // Send other commands to server
                client.sendMessage(userInput);
                System.out.print("> ");
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