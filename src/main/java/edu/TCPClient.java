package edu;

/**
 * TCPClient: Client application that connects to the server and handles user input/output.
 * Allows users to send messages, manage tasks, and transfer files through the TCP connection.
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
    
    private void sendFile(String target, String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            System.err.println("Error: File not found: " + filePath);
            return;
        }
        
        if (!file.isFile()) {
            System.err.println("Error: Path is not a file: " + filePath);
            return;
        }
        
        String filename = file.getName();
        long fileSize = file.length();
        
        // Determine type and destination from target
        String type;
        String destination;
        if (target.startsWith("@")) {
            type = "user";
            destination = target.substring(1);
        } else if (target.startsWith("#")) {
            type = "channel";
            destination = target.substring(1);
        } else {
            System.err.println("Error: Target must start with @ (user) or # (channel)");
            return;
        }
        
        // Send command using sendFile format (server will understand)
        // Format: sendFile <type> <destination> <fileSize> <filename>
        // Filename is last to handle spaces in filename
        String command = "sendFile " + type + " " + destination + " " + fileSize + " " + filename;
        sendMessage(command);
        
        // Wait for READY_FOR_FILE response
        if (!waitForResponse("READY_FOR_FILE", 50)) {
            System.err.println("Error: Server did not respond with READY_FOR_FILE");
            return;
        }
        
        if (lastResponse != null && lastResponse.equals("READY_FOR_FILE")) {
            // Read file and encode as Base64
            try (FileInputStream fileIn = new FileInputStream(file)) {
                byte[] fileBytes = new byte[(int) fileSize];
                int totalBytesRead = 0;
                int bytesRead;
                while (totalBytesRead < fileSize && (bytesRead = fileIn.read(fileBytes, totalBytesRead, (int)fileSize - totalBytesRead)) != -1) {
                    totalBytesRead += bytesRead;
                }
                
                // Encode to Base64 and send as text
                String base64Data = java.util.Base64.getEncoder().encodeToString(fileBytes);
                sendMessage(base64Data);
                
                System.out.println("File sent: " + filename);
            }
            
            // Wait for FILE SENT response
            waitForResponse(null, 50);
            
            if (lastResponse != null && lastResponse.startsWith("FILE SENT")) {
                System.out.println("[Server] " + lastResponse);
            } else if (lastResponse != null && lastResponse.startsWith("ERROR")) {
                System.err.println("[Server] " + lastResponse);
            }
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
                    } else if (line.startsWith("incomingFile ")) {
                        // Receive file - format: incomingFile filename size base64data
                        handleIncomingFile(line);
                    } else {
                        lastResponse = line;
                        responseReceived = true;
                        System.out.println("\n[Server] " + line);
                        if (!line.equals("LOGIN OK") && !line.equals("REGISTER OK") && !line.startsWith("MESSAGE SENT") && !line.equals("READY_FOR_FILE")) {
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
    
    // Helper method to wait for server response
    private boolean waitForResponse(String expectedResponse, int maxWaitCount) {
        responseReceived = false;
        lastResponse = null;
        int waitCount = 0;
        while (!responseReceived && waitCount < maxWaitCount) {
            try {
                Thread.sleep(100);
                waitCount++;
            } catch (InterruptedException e) {
                break;
            }
        }
        if (expectedResponse != null) {
            return lastResponse != null && lastResponse.equals(expectedResponse);
        }
        return responseReceived;
    }
    
    private void handleIncomingFile(String fileLine) {
        try {
            // Format: incomingFile <base64_filename> <size> <base64data>
            String[] parts = fileLine.split(" ", 4);
            if (parts.length >= 4) {
                String encodedFilename = parts[1];
                String filename = new String(
                        java.util.Base64.getDecoder().decode(encodedFilename),
                        java.nio.charset.StandardCharsets.UTF_8
                );
                long fileSize = Long.parseLong(parts[2]);
                String base64Data = parts[3];
                
                System.out.println("\n[File Received] " + filename + " (" + fileSize + " bytes)");
                
                // Decode Base64 data
                byte[] fileData = java.util.Base64.getDecoder().decode(base64Data);
                
                // Create downloads directory if it doesn't exist
                File downloadDir = new File("downloads");
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs();
                }
                
                // Save file
                File outputFile = new File(downloadDir, filename);
                try (FileOutputStream fileOut = new FileOutputStream(outputFile)) {
                    fileOut.write(fileData);
                }
                
                System.out.println("File saved to: " + outputFile.getAbsolutePath());
                System.out.print("> ");
            }
        } catch (Exception e) {
            System.err.println("\nError receiving file: " + e.getMessage());
            System.out.print("> ");
        }
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
                    client.waitForResponse(null, 20);
                    
                    // Check response
                        if (client.lastResponse != null) {
                            if (client.lastResponse.equals("LOGIN OK") || client.lastResponse.equals("REGISTER OK")) {
                                System.out.println("\n " + (userInput.startsWith("login") ? "Login" : "Registration") + " successful!");
                                System.out.println("Available commands:");
                                System.out.println("  send #<channel> <message>");
                                System.out.println("  send @<username> <message>");
                                System.out.println("  send #<channel> <file_path>");
                                System.out.println("  send @<username> <file_path>");
                                System.out.println("  createTask description <description> [status <status>] [deadline <deadline>] [assignee <username>]");
                                System.out.println("  viewTasks or viewTasks [username]");
                                System.out.println("  updateTask <task_id> description <description> [status <status>] [deadline <deadline>] [assignee <username>]");
                                System.out.println("  deleteTask <task_id>");
                                System.out.println("  viewDirectMessages");
                                System.out.println("  viewChannelMessages <channel>");
                                System.out.print("\n> ");
                        } else if (client.lastResponse.equals("LOGIN FAILED")) {
                            System.err.println("\n Login failed. Check username/password or register first.");
                    System.out.print("> ");
                } else {
                            System.err.println("\n " + client.lastResponse);
                            System.out.print("> ");
                        }
                    } else {
                        System.err.println("\n No response from server.");
                    System.out.print("> ");
                    }
                    continue;
                }
                
                // Handle send command - check if it's a file or message
                if (userInput.startsWith("send ")) {
                    // Robust parsing: split into 3 parts: [send] [target] [content...]
                    String[] parts = userInput.split("\\s+", 3);
                    if (parts.length < 3) {
                        System.err.println("Error: Missing content (message or file path)");
                        System.out.print("> ");
                        continue;
                    }
                    
                    String target = parts[1];   // e.g., @kim or #general
                    String content = parts[2];  // e.g., ./CG - 10 - Light and Color.pdf or hello
                    
                    // Quick check: if content looks like a file path, try to find it
                    // Only check if it starts with ./ or / or ~ or contains a file extension pattern
                    boolean mightBeFile = content.startsWith("./") || 
                                         content.startsWith("/") || 
                                         content.startsWith("~") ||
                                         (content.contains(".") && content.matches(".*\\.[a-zA-Z0-9]{1,10}$"));
                    
                    if (mightBeFile) {
                        // Get current working directory
                        String currentDir = System.getProperty("user.dir");
                        
                        // Normalize path: remove leading ./ if present
                        String normalizedPath = content;
                        if (normalizedPath.startsWith("./")) {
                            normalizedPath = normalizedPath.substring(2);
                        }
                        
                        // Try multiple path variations
                        File file = null;
                        String actualFilePath = null;
                        
                        // Build list of paths to try
                        String[] pathsToTry = {
                            content,  // Original path
                            normalizedPath,  // Without ./
                            new File(currentDir, normalizedPath).getPath(),  // Current dir + normalized
                            new File(currentDir, content).getPath(),  // Current dir + original
                            new File(currentDir, normalizedPath).getAbsolutePath(),  // Absolute normalized
                            new File(currentDir, content).getAbsolutePath()  // Absolute original
                        };
                        
                        // Try each path
                        for (String path : pathsToTry) {
                            file = new File(path);
                            try {
                                // Use getCanonicalFile() to resolve . and .. and symlinks
                                File canonicalFile = file.getCanonicalFile();
                                if (canonicalFile.exists() && canonicalFile.isFile()) {
                                    actualFilePath = canonicalFile.getAbsolutePath();
                                    break;
                                }
                            } catch (IOException e) {
                                // If canonical path fails, try regular exists check
                                if (file.exists() && file.isFile()) {
                                    actualFilePath = file.getAbsolutePath();
                                    break;
                                }
                            }
                        }
                        
                        if (actualFilePath != null) {
                            // It's a file, send file using the actual file path
                            try {
                                client.sendFile(target, actualFilePath);
                            } catch (IOException e) {
                                System.err.println("Error sending file: " + e.getMessage());
                            }
                            System.out.print("> ");
                            continue;
                        }
                    }
                    // Not a file, send as regular message
                    client.sendMessage(userInput);
                    System.out.print("> ");
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
