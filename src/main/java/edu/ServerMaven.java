package edu;

import java.io.IOException;
import java.net.*;
import java.sql.*;


public class ServerMaven {
    
    private static final String USERS_DB_URL = "jdbc:sqlite:users.db";
    private static ServerSocket serverSocket = null;
    private static volatile boolean running = true;

    public static void main(String[] args) {
        
        int port = Integer.parseInt(args[0]);

        System.out.println("TCP Server running on " + port);
        System.out.println("Press Ctrl+C to stop the server.");
        
        // Add shutdown hook for graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down server...");
            running = false;
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing server socket: " + e.getMessage());
                }
            }
            System.out.println("Server stopped.");
        }));
        
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (Exception e){
            System.err.println("SQLite driver missing");
            return;
        }
        // Create initial connection for table setup
        try (Connection setupConn = DriverManager.getConnection(USERS_DB_URL)) {
            createUsersTableIfNotExists(setupConn);
            createChannelMessagesTableIfNotExists(setupConn);
            createDirectMessagesTableIfNotExists(setupConn);
            createTasksTableIfNotExists(setupConn);
            createFilesTableIfNotExists(setupConn);
        } catch (SQLException e) {
            System.err.println("Error setting up database: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        try {
            serverSocket = new ServerSocket(port);

            while (running){
                try {
                    Socket client = serverSocket.accept();
                    System.out.println("Client connected: " + client.getInetAddress());
                    // Each ClientHandler gets its own connection
                    new Thread(new ClientHandler(client)).start();
                } catch (IOException e) {
                    if (running) {
                        // Only print error if we're still supposed to be running
                        e.printStackTrace();
                    }
                    // If serverSocket was closed (shutdown), break the loop
                    break;
                }
            } 
            
        } catch (Exception e){
            e.printStackTrace();
        } finally {
            if (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    System.err.println("Error closing server socket: " + e.getMessage());
                }
            }
        }
    }

     private static void createUsersTableIfNotExists(Connection conn) throws SQLException {
        String createTableSQL = "CREATE TABLE IF NOT EXISTS users (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "username TEXT NOT NULL," +
                "password TEXT NOT NULL," +
                "ip_address TEXT NOT NULL," +
                "hostname TEXT NOT NULL," +
                "loggedin INTEGER DEFAULT 0" +
                ");";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSQL);
            
            // Add loggedin column if it doesn't exist (migration)
            try {
                stmt.execute("ALTER TABLE users ADD COLUMN loggedin INTEGER DEFAULT 0");
            } catch (SQLException e) {
                // Column already exists, ignore
            }
        }
    }

    private static void createDirectMessagesTableIfNotExists(Connection conn) throws SQLException {
        String createTableSQL = "CREATE TABLE IF NOT EXISTS direct_messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "sender TEXT NOT NULL," +
                "receiver TEXT NOT NULL," +
                "message TEXT NOT NULL," +
                "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP" +
                ");";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSQL);
        }
    }

    private static void createChannelMessagesTableIfNotExists(Connection conn) throws SQLException {
        String createTableSQL = "CREATE TABLE IF NOT EXISTS channel_messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "sender TEXT NOT NULL," +
                "channel TEXT NOT NULL," +
                "message TEXT NOT NULL," +
                "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP" +
                ");";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSQL);
        }
    }

    private static void createTasksTableIfNotExists(Connection conn) throws SQLException {
    String createTableSQL = "CREATE TABLE IF NOT EXISTS tasks (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "creator TEXT NOT NULL," +
            "assignee TEXT," +
            "description TEXT NOT NULL," +
            "status TEXT DEFAULT 'pending'," +
            "deadline TEXT" +
            ");";
    try (Statement stmt = conn.createStatement()) {
        stmt.execute(createTableSQL);
            
            // Add assignee column if table already exists without it
            try {
                stmt.execute("ALTER TABLE tasks ADD COLUMN assignee TEXT");
            } catch (SQLException e) {
                // Column already exists, ignore
            }
            
            // Add status column if table already exists without it
            try {
                stmt.execute("ALTER TABLE tasks ADD COLUMN status TEXT DEFAULT 'pending'");
            } catch (SQLException e) {
                // Column already exists, ignore
            }
            
            // Add deadline column if table already exists without it
            try {
                stmt.execute("ALTER TABLE tasks ADD COLUMN deadline TEXT");
            } catch (SQLException e) {
                // Column already exists, ignore
            }
    }
    }

    public static void createFilesTableIfNotExists(Connection conn) throws SQLException {
        String createTableSQL = "CREATE TABLE IF NOT EXISTS files (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "sender TEXT NOT NULL," +
                "destination_type TEXT NOT NULL CHECK(destination_type IN ('CHANNEL', 'USER'))," +
                "destination_name TEXT NOT NULL," +
                "filename TEXT NOT NULL," +
                "file_data BLOB NOT NULL," +
                "uploaded_at DATETIME DEFAULT CURRENT_TIMESTAMP" +
                        ");";
        
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSQL);
        }
    }
}