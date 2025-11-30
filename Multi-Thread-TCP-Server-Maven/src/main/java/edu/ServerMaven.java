package edu;

import java.io.*;
import java.net.*;
import java.sql.*;


public class ServerMaven {
    
    private static final String USERS_DB_URL = "jdbc:sqlite:users.db";

    public static void main(String[] args) {
        
        int port = Integer.parseInt(args[0]);

        System.out.println("TCP Server running on port " + port);

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (Exception e){
            System.err.println("SQLite driver missing");
            return;
        }
        try (Connection conn = DriverManager.getConnection(USERS_DB_URL); ServerSocket serverSocket = new ServerSocket(port)){

            createUsersTableIfNotExists(conn);

            while (true){
                Socket client = serverSocket.accept();
                System.out.println("Client connected: " + client.getInetAddress());                    new Thread(new ClientHandler(client, conn)).start();
            } 
            
        } catch (Exception e){
            e.printStackTrace();
        }
    }

     private static void createUsersTableIfNotExists(Connection conn) throws SQLException {
        String createTableSQL = "CREATE TABLE IF NOT EXISTS users (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "username TEXT NOT NULL," +
                "password TEXT NOT NULL," +
                "ip_address TEXT NOT NULL," +
                "hostname TEXT NOT NULL" +
                ");";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSQL);
        }
    }

    static class ClientHandler implements Runnable{

        private Socket client;
        private Connection conn;

        public ClientHandler(Socket client, Connection conn){
            this.client = client;
            this.conn = conn;
        }

        @Override
        public void run(){
            try{
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(client.getInputStream()));

                PrintWriter out = new PrintWriter(client.getOutputStream(), true);

                String senderIP = client.getInetAddress().getHostAddress();
                String senderHost = client.getInetAddress().getHostName();

                String line;

                while ((line = in.readLine()) != null){
                    System.out.println("Received: " + line);

                    String[] parts = line.trim().split("\\s+");

                    if (parts.length == 0)
                        continue;

                    switch (parts[0]){
                        case "login" -> handleLogin(parts, out, senderIP, senderHost);
                        case "register"-> handleRegister(parts, out, senderIP, senderHost);
                        default -> out.println("ERROR: Unknown command");
                    }
                }

                
            } catch (Exception e){
                e.printStackTrace();
            } finally {
                try { client.close(); } catch (IOException ignored) {}
            }
        }

        private void handleLogin(String[] parts, PrintWriter out, String senderIP, String senderHost){

            if (parts.length < 3){
                out.println("ERROR: usage: login <username> <password>");
                return;
            }

            String sql = """
                    SELECT 1 FROM users
                    WHERE username = ? AND password = ?
                    AND ip_address = ? AND hostname = ?
                    """;
            try (PreparedStatement pstmt = conn.prepareStatement(sql)){
                pstmt.setString(1, parts[1]);
                pstmt.setString(2, parts[2]);
                pstmt.setString(3, senderIP);
                pstmt.setString(4, senderHost);

                ResultSet rs = pstmt.executeQuery();
                if (rs.next()){
                    out.println("LOGIN OK");
                } else {
                    out.println("LOGIN FAILED");        
                }
            } catch (SQLException e){
                out.println("LOGIN ERROR");
                e.printStackTrace();
            }
        }

        private void handleRegister(String[] parts, PrintWriter out, String senderIP, String senderHost){

            if (parts.length < 3){
                out.println("ERROR: usage: register <username> <password>");
                return;
            }

            String sql = """
                    INSERT INTO users (username, password, ip_address, hostname)
                    VALUES (?, ?, ?, ?)
                    """;
            try (PreparedStatement pstmt = conn.prepareStatement(sql)){
                pstmt.setString(1, parts[1]);
                pstmt.setString(2, parts[2]);
                pstmt.setString(3, senderIP);
                pstmt.setString(4, senderHost);

                pstmt.executeUpdate();
                out.println("REGISTER OK");
            } catch (SQLException e){
                out.println("REGISTER FAIED");
                e.printStackTrace();
            }
        }
    }
}