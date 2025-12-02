package edu;

import java.net.*;
import java.sql.*;

public class ServerMaven {
    
    private static final String USERS_DB_URL = "jdbc:sqlite:users.db";

    public static void main(String[] args) {
        
        int port = Integer.parseInt(args[0]);

        System.out.println("TCP Server running on " + port);
        
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (Exception e){
            System.err.println("SQLite driver missing");
            return;
        }
        try (Connection conn = DriverManager.getConnection(USERS_DB_URL); ServerSocket serverSocket = new ServerSocket(port)){

            createUsersTableIfNotExists(conn);
            createDirectMessagesTableIfNotExists(conn);

            while (true){
                Socket client = serverSocket.accept();
                System.out.println("Client connected: " + client.getInetAddress());   new Thread(new ClientHandler(client, conn)).start();
            } 
            
        } catch (Exception e){
            e.printStackTrace();
        }
    }

     private static void createUsersTableIfNotExists(Connection conn) throws SQLException {
        String createTableSQL = "CREATE TABLE IF NOT EXISTS users (" +
                "username TEXT NOT NULL," +
                "password TEXT NOT NULL," +
                "ip_address TEXT NOT NULL," +
                "hostname TEXT NOT NULL," +
                "PRIMARY KEY (username, ip_address, hostname)" +
                ");";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSQL);
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

    
}