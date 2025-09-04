import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.text.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

class DBManager {
    // JDBC connection details for MySQL database 'metrosystemdb' on localhost
    static final String JDBC_URL = "jdbc:mysql://localhost:3306/metrosystemdb";
    static final String DB_USER = "root";
    static final String DB_PASS = "";

    static {
        // Load MySQL JDBC driver once when class is loaded
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (Exception ex) {
            System.err.println("JDBC Driver not found: " + ex.getMessage());
        }
    }

    // Obtain database connection for all DB operations
    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(JDBC_URL, DB_USER, DB_PASS);
    }

    // Insert new user into the users table
    public static boolean insertUser(String username, String password, double walletBalance, String role) {
        try (Connection conn = getConnection()) {
            String sql = "INSERT INTO users (username, password, walletBalance, role) VALUES (?, ?, ?, ?)";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, username);
            ps.setString(2, password);
            ps.setDouble(3, walletBalance);
            ps.setString(4, role);
            return ps.executeUpdate() > 0; // returns true if insert successful
        } catch (SQLException e) {
            System.err.println("Error inserting user: " + e.getMessage());
            return false; // failure inserting user
        }
    }

    // Check if a username exists in the database
    public static boolean usernameExists(String username) {
        String sql = "SELECT 1 FROM users WHERE username=?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            return rs.next(); // true if at least one record found
        } catch (SQLException e) {
            System.err.println("Error checking username: " + e.getMessage());
            return false; // error treated as username not existing
        }
    }

    // Mark a ticket as cancelled by setting cancelled flag to true
    public static boolean cancelTicket(int ticketId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE tickets SET cancelled=TRUE WHERE ticketId=?")) {
            ps.setInt(1, ticketId);
            int affected = ps.executeUpdate();
            return affected > 0;  // trigger logs cancellation automatically
        } catch (SQLException e) {
            System.err.println("Error cancelling ticket: " + e.getMessage());
            return false;
        }
    }

    // Insert a new support ticket linked to feedback with initial status
    public static int insertSupportTicketReturnId(int feedbackId, String status) {
        try (Connection conn = getConnection()) {
            String sql = "INSERT INTO support_tickets (feedbackId, status) VALUES (?, ?)";
            PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, feedbackId);
            ps.setString(2, status);
            int affectedRows = ps.executeUpdate();
            if (affectedRows == 0) return -1;
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
                else return -1;
            }
        } catch (SQLException e) {
            System.err.println("Error inserting support ticket: " + e.getMessage());
            return -1;
        }
    }

    // Insert feedback and return its generated feedbackId or -1 on failure
    public static int insertFeedbackReturnId(String username, String text, String type) {
        try (Connection conn = getConnection()){
            String sql = "INSERT INTO feedbacks (username, text, type) VALUES (?, ?, ?)";
            PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, text);
            ps.setString(3, type);
            int affectedRows = ps.executeUpdate();
            if (affectedRows == 0) return -1; // insert failed
            try (ResultSet generatedKeys = ps.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    return generatedKeys.getInt(1); // return new feedbackId
                } else {
                    return -1; // no key generated
                }
            }
        } catch (SQLException e) {
            System.err.println("Error inserting feedback: " + e.getMessage());
            return -1;
        }
    }
    
    // Update support ticket status and resolvedDate if applicable
    public static boolean updateSupportTicketStatus(int ticketId, String status, Date resolvedDate) {
        try (Connection conn = getConnection()) {
            String sql = "UPDATE support_tickets SET status=?, resolvedDate=? WHERE ticketId=?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, status);
            if(resolvedDate == null) {
                ps.setNull(2, java.sql.Types.DATE);
            } else {
                ps.setDate(2, new java.sql.Date(resolvedDate.getTime()));
            }
            ps.setInt(3, ticketId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error updating ticket status: " + e.getMessage());
            return false;
        }
    }

    // Fetch support ticket by ticketId and assigned staff username
    public static SupportTicket getSupportTicketByIdAndStaff(int ticketId, String staffUsername) {
        SupportTicket ticket = null;
        String sql = "SELECT st.ticketId, st.status, st.createdDate, st.resolvedDate, st.assignedStaffUsername, " +
                "fb.username AS fbUser, fb.text, fb.type " +
                "FROM support_tickets st JOIN feedbacks fb ON st.feedbackId = fb.feedbackId " +
                "WHERE st.ticketId = ? AND st.assignedStaffUsername = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, ticketId);
            ps.setString(2, staffUsername);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Feedback fb = new Feedback(rs.getString("fbUser"), rs.getString("text"), rs.getString("type"));
                SupportTicket st = new SupportTicket(fb);
                st.ticketId = rs.getInt("ticketId");
                st.status = rs.getString("status");
                st.createdDate = rs.getTimestamp("createdDate");
                st.resolvedDate = rs.getTimestamp("resolvedDate");
                st.assignedStaffUsername = rs.getString("assignedStaffUsername");
                ticket = st;
            }
        } catch (SQLException e) {
            System.err.println("Error fetching support ticket by ID and staff: " + e.getMessage());
        }
        return ticket;
    }

    // Insert ticket details into tickets table and return generated ticketId or -1 on failure
    public static int insertTicket(String username, String source, String dest, int passengers, double fare, Date travelDate, boolean cancelled) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tickets (username, source, destination, passengers, fare, travelDate, cancelled, bookingDate) VALUES (?, ?, ?, ?, ?, ?, ?, NOW())", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, source);
            ps.setString(3, dest);
            ps.setInt(4, passengers);
            ps.setDouble(5, fare);
            ps.setDate(6, new java.sql.Date(travelDate.getTime()));
            ps.setBoolean(7, cancelled);

            int affected = ps.executeUpdate();
            if (affected == 0) return -1;

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error inserting ticket: " + e.getMessage());
        }
        return -1;
    }

    // Retrieve list of Ticket objects for a specific user, ordered by booking date desc
    public static List<Ticket> getTicketsByUser(String username) {
        List<Ticket> tickets = new ArrayList<>();
        try (Connection conn = getConnection()) {
            String sql = "SELECT * FROM tickets WHERE username=? ORDER BY bookingDate DESC";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Ticket t = new Ticket(
                        rs.getString("username"),
                        rs.getString("source"),
                        rs.getString("destination"),
                        rs.getInt("passengers"),
                        rs.getDouble("fare"),
                        rs.getDate("travelDate")
                );
                t.ticketId = rs.getInt("ticketId");
                t.cancelled = rs.getBoolean("cancelled");
                tickets.add(t);
            }
        } catch (SQLException e) {
            System.err.println("Error fetching tickets: " + e.getMessage());
        }
        return tickets;
    }

    // Setup database tables if they don't exist
    public static void setupDatabase() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            // USERS TABLE
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS users (" +
                            "username VARCHAR(50) PRIMARY KEY," +
                            "password VARCHAR(100) NOT NULL," +
                            "walletBalance DOUBLE NOT NULL," +
                            "role VARCHAR(20) NOT NULL" +
                            ")"
            );

            // TICKETS TABLE
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS tickets (" +
                            "ticketId INT AUTO_INCREMENT PRIMARY KEY," +
                            "username VARCHAR(50) NOT NULL," +
                            "source VARCHAR(50) NOT NULL," +
                            "destination VARCHAR(50) NOT NULL," +
                            "passengers INT NOT NULL," +
                            "fare DOUBLE NOT NULL," +
                            "travelDate DATE NOT NULL," +
                            "cancelled BOOLEAN NOT NULL," +
                            "bookingDate DATETIME NOT NULL," +
                            "FOREIGN KEY (username) REFERENCES users(username) ON DELETE CASCADE" +
                            ")"
            );

            // FEEDBACKS TABLE
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS feedbacks (" +
                            "feedbackId INT AUTO_INCREMENT PRIMARY KEY," +
                            "username VARCHAR(50) NOT NULL," +
                            "text VARCHAR(255) NOT NULL," +
                            "type VARCHAR(20) NOT NULL," +
                            "timestamp DATETIME DEFAULT CURRENT_TIMESTAMP," +
                            "FOREIGN KEY (username) REFERENCES users(username) ON DELETE CASCADE" +
                            ")"
            );

            // SUPPORT TICKETS TABLE
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS support_tickets (" +
                            "ticketId INT AUTO_INCREMENT PRIMARY KEY," +
                            "feedbackId INT NOT NULL," +
                            "status VARCHAR(20) NOT NULL," +
                            "assignedStaffUsername VARCHAR(50)," +
                            "createdDate DATETIME DEFAULT CURRENT_TIMESTAMP," +
                            "resolvedDate DATETIME," +
                            "FOREIGN KEY (feedbackId) REFERENCES feedbacks(feedbackId) ON DELETE CASCADE," +
                            "FOREIGN KEY (assignedStaffUsername) REFERENCES users(username) ON DELETE SET NULL" +
                            ")"
            );

            // ANNOUNCEMENTS TABLE (for admin)
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS announcements (" +
                            "id INT AUTO_INCREMENT PRIMARY KEY," +
                            "message VARCHAR(255) NOT NULL," +
                            "createdDate DATETIME DEFAULT CURRENT_TIMESTAMP" +
                            ")"
            );

            // METRO CARDS TABLE
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS metro_cards (" +
                            "cardNumber INT AUTO_INCREMENT PRIMARY KEY," +
                            "username VARCHAR(50) NOT NULL," +
                            "balance DOUBLE NOT NULL," +
                            "autoRechargeEnabled BOOLEAN NOT NULL," +
                            "minBalanceThreshold DOUBLE NOT NULL," +
                            "FOREIGN KEY (username) REFERENCES users(username) ON DELETE CASCADE)"
            );

            // MONTHLY PASSES TABLE
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS monthly_passes (" +
                            "passId INT AUTO_INCREMENT PRIMARY KEY," +
                            "username VARCHAR(50) NOT NULL," +
                            "source VARCHAR(50) NOT NULL," +
                            "destination VARCHAR(50) NOT NULL," +
                            "purchaseDate DATE NOT NULL," +
                            "expiryDate DATE NOT NULL," +
                            "price DOUBLE NOT NULL," +
                            "FOREIGN KEY (username) REFERENCES users(username) ON DELETE CASCADE)"
            );

            // STATION LOCATIONS TABLE (for geo support)
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS station_locations (" +
                            "name VARCHAR(50) PRIMARY KEY," +
                            "x DOUBLE, " +
                            "y DOUBLE" +
                            ")"
            );

        } catch (SQLException e) {
            System.err.println("Error creating tables: " + e.getMessage());
        }
    }// Method to call stored procedure for audit log
    public static boolean insertAuditLog(String username, String action) {
        try (Connection conn = getConnection();
             CallableStatement cs = conn.prepareCall("{CALL InsertAuditLog(?, ?)}")) {
            cs.setString(1, username);
            cs.setString(2, action);
            cs.execute();
            return true;
        } catch (SQLException e) {
            System.err.println("Error inserting audit log: " + e.getMessage());
            return false;
        }
    }

    // Method to call function to get latest audit log
    protected static String getLatestAuditLog(String username) {
        String result = null;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT GetLatestAuditLog(?) AS last_action")) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                result = rs.getString("last_action");
            }
        } catch (SQLException e) {
            System.err.println("Error fetching latest audit log: " + e.getMessage());
        }
        return result;
    }

    // Remove a user by username (cascades if foreign keys)
    public static boolean removeUser(String username) {
        try (Connection conn = getConnection()) {
            String sql = "DELETE FROM users WHERE username=?";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error removing user: " + e.getMessage());
            return false;
        }
    }

    // Update user wallet balance in users table
    public static boolean updateUserWalletBalance(String username, double newBalance) {
        String sql = "UPDATE users SET walletBalance=? WHERE username=?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, newBalance);
            ps.setString(2, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error updating wallet balance: " + e.getMessage());
            return false;
        }
    }

    // Update user password (hashed) in users table
    public static boolean updateUserPassword(String username, String newHashedPassword) {
        String sql = "UPDATE users SET password=? WHERE username=?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newHashedPassword);
            ps.setString(2, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error updating user password: " + e.getMessage());
            return false;
        }
    }

    // Insert a new MetroCard record and return generated cardNumber or -1 on failure
    public static int insertMetroCard(String username, double balance, boolean autoRechargeEnabled, double minBalanceThreshold) {
        String sql = "INSERT INTO metro_cards (username, balance, autoRechargeEnabled, minBalanceThreshold) VALUES (?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setDouble(2, balance);
            ps.setBoolean(3, autoRechargeEnabled);
            ps.setDouble(4, minBalanceThreshold);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("Error inserting MetroCard: " + e.getMessage());
        }
        return -1;
    }

    // Update MetroCard balance, auto recharge flag and min threshold by cardNumber
    public static boolean updateMetroCard(int cardNumber, double balance, boolean autoRechargeEnabled, double minBalanceThreshold) {
        String sql = "UPDATE metro_cards SET balance=?, autoRechargeEnabled=?, minBalanceThreshold=? WHERE cardNumber=?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, balance);
            ps.setBoolean(2, autoRechargeEnabled);
            ps.setDouble(3, minBalanceThreshold);
            ps.setInt(4, cardNumber);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error updating MetroCard: " + e.getMessage());
            return false;
        }
    }

    // Fetch MetroCard details by username and return MetroCard object or null if not found
    public static MetroCard getMetroCardByUsername(String username) {
        String sql = "SELECT * FROM metro_cards WHERE username=?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    MetroCard card = new MetroCard(
                            rs.getInt("cardNumber"), // card ID from DB
                            rs.getDouble("balance"),
                            null // owner to be set later if needed
                    );
                    card.setAutoRecharge(rs.getBoolean("autoRechargeEnabled"));
                    card.setMinBalanceThreshold(rs.getDouble("minBalanceThreshold"));
                    return card;
                }
            }
        } catch (SQLException e) {
            System.err.println("Error fetching MetroCard: " + e.getMessage());
        }
        return null;
    }

    // Insert monthly pass purchase info and return generated passId or -1 if failed
    public static int insertMonthlyPass(String username, String source, String dest, Date purchaseDate, Date expiryDate, double price) {
        String sql = "INSERT INTO monthly_passes (username, source, destination, purchaseDate, expiryDate, price) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, source);
            ps.setString(3, dest);
            ps.setDate(4, new java.sql.Date(purchaseDate.getTime()));
            ps.setDate(5, new java.sql.Date(expiryDate.getTime()));
            ps.setDouble(6, price);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("Error inserting monthly pass: " + e.getMessage());
        }
        return -1;
    }

    // Get active monthly passes routes for a user as list of strings "source->destination"
    public static List<String> getMonthlyPassRoutesByUsername(String username) {
        List<String> passes = new ArrayList<>();
        String sql = "SELECT source, destination FROM monthly_passes WHERE username=? AND expiryDate >= CURRENT_DATE";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    passes.add(rs.getString("source") + "->" + rs.getString("destination"));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error fetching monthly passes: " + e.getMessage());
        }
        return passes;
    }

    // Insert or update a station's location coordinates in station_locations table
    public static boolean insertOrUpdateStationLocation(String name, double x, double y) {
        String sql = "INSERT INTO station_locations (name, x, y) VALUES (?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE x=?, y=?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setDouble(2, x);
            ps.setDouble(3, y);
            ps.setDouble(4, x);
            ps.setDouble(5, y);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error updating location: " + e.getMessage());
            return false;
        }
    }

    // Retrieve list of all users with role USER from the database
    public static List<User> getAllUsers() {
        List<User> users = new ArrayList<>();
        String sql = "SELECT username, password, walletBalance FROM users WHERE role='USER'";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                User u = new User(rs.getString("username"), rs.getString("password"), rs.getDouble("walletBalance"));
                users.add(u);
            }
        } catch (SQLException e) {
            System.err.println("Error fetching all users: " + e.getMessage());
        }
        return users;
    }

    // Retrieve all station names as a Set of strings
    public static Set<String> getAllStationNames() {
        Set<String> stations = new HashSet<>();
        String sql = "SELECT name FROM station_locations";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                stations.add(rs.getString("name"));
            }
        } catch (SQLException e) {
            System.err.println("Error fetching stations: " + e.getMessage());
        }
        return stations;
    }

    // Get coordinates of a station from the DB as a StationLocation object
    public static StationLocation getStationLocation(String name) {
        String sql = "SELECT x, y FROM station_locations WHERE name=?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new StationLocation(name, rs.getDouble("x"), rs.getDouble("y"));
                }
            }
        } catch (SQLException e) {
            System.err.println("Error fetching station location: " + e.getMessage());
        }
        return null;
    }

    // Simple container class to hold station location details
    public static class StationLocation {
        public String name;
        public double x, y;
        public StationLocation(String name, double x, double y) {
            this.name = name;
            this.x = x;
            this.y = y;
        }
        @Override
        public String toString() {
            return String.format("%s [x=%.2f, y=%.2f]", name, x, y);
        }
    }

    // Backup the current list of station names to a text file for restore
    public static boolean backupCurrentStations(Set<String> stations) {
        try (PrintWriter pw = new PrintWriter(new File("stations_backup.txt"))) {
            for (String station : stations) {
                pw.println(station);
            }
            System.out.println("✅ Backup complete: stations_backup.txt created.");
            return true;
        } catch (IOException e) {
            System.out.println("❌ Error creating backup: " + e.getMessage());
            return false;
        }
    }

    // Restore stations from backup file, resetting station_locations table
    public static boolean restoreStationsFromBackup() {
        File file = new File("stations_backup.txt");
        if (!file.exists()) {
            System.out.println("❌ No backup file found.");
            return false;
        }
        try (Scanner scanner = new Scanner(file);
             Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            // Delete all current stations
            stmt.executeUpdate("DELETE FROM station_locations");

            // Insert stations with default coordinates (0,0)
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO station_locations (name, x, y) VALUES (?, 0, 0)"
            );
            while (scanner.hasNextLine()) {
                String station = scanner.nextLine().trim();
                if (!station.isEmpty()) {
                    ps.setString(1, station);
                    ps.executeUpdate();
                }
            }
            System.out.println("✅ Rollback/restore complete from backup file.");
            return true;
        } catch (Exception e) {
            System.out.println("❌ Error restoring backup: " + e.getMessage());
            return false;
        }
    }

    // Clear all tables data in the database (used for resetting system)
    public static boolean clearAllData() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            // Disable foreign key checks to avoid constraint errors on deletion
            stmt.execute("SET FOREIGN_KEY_CHECKS=0");

            // Delete data in dependent order
            stmt.executeUpdate("DELETE FROM support_tickets");
            stmt.executeUpdate("DELETE FROM feedbacks");
            stmt.executeUpdate("DELETE FROM tickets");
            stmt.executeUpdate("DELETE FROM monthly_passes");
            stmt.executeUpdate("DELETE FROM metro_cards");
            stmt.executeUpdate("DELETE FROM station_locations");
            stmt.executeUpdate("DELETE FROM announcements");
            stmt.executeUpdate("DELETE FROM users");

            // Re-enable FK checks after deletion
            stmt.execute("SET FOREIGN_KEY_CHECKS=1");

            return true;
        } catch (SQLException e) {
            System.err.println("Error clearing all data: " + e.getMessage());
            return false;
        }
    }
    // Insert or update station info (description, facilities)
    public static boolean insertOrUpdateStationInfo(String name, String description, boolean hasRestrooms, boolean hasParking, boolean hasWifi) {
        String sql = "INSERT INTO station_info (name, description, hasRestrooms, hasParking, hasWifi) " +
                "VALUES (?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE description=?, hasRestrooms=?, hasParking=?, hasWifi=?";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setBoolean(3, hasRestrooms);
            ps.setBoolean(4, hasParking);
            ps.setBoolean(5, hasWifi);
            ps.setString(6, description);
            ps.setBoolean(7, hasRestrooms);
            ps.setBoolean(8, hasParking);
            ps.setBoolean(9, hasWifi);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error inserting/updating station info: " + e.getMessage());
            return false;
        }
    }
    public static List<SupportTicket> getAssignedTicketsFromDB(String staffUsername) {
        List<SupportTicket> tickets = new ArrayList<>();
        String sql = "SELECT st.ticketId, st.status, st.createdDate, st.resolvedDate, " +
                "fb.username, fb.text, fb.type " +
                "FROM support_tickets st " +
                "JOIN feedbacks fb ON st.feedbackId = fb.feedbackId " +
                "WHERE st.assignedStaffUsername = ? " +
                "ORDER BY st.createdDate DESC";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, staffUsername);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Feedback fb = new Feedback(
                        rs.getString("username"),
                        rs.getString("text"),
                        rs.getString("type")
                );
                SupportTicket ticket = new SupportTicket(fb);
                ticket.ticketId = rs.getInt("ticketId");
                ticket.status = rs.getString("status");
                ticket.createdDate = rs.getTimestamp("createdDate");
                ticket.resolvedDate = rs.getTimestamp("resolvedDate"); // nullable
                ticket.assignedStaffUsername = staffUsername;
                tickets.add(ticket);
            }
        } catch (SQLException e) {
            System.err.println("Error fetching assigned tickets: " + e.getMessage());
        }
        return tickets;
    }

    public static List<Feedback> getFeedbacksByUsername(String username) {
        List<Feedback> feedbackList = new ArrayList<>();
        String sql = "SELECT feedbackId, text, type, timestamp FROM feedbacks WHERE username=? ORDER BY timestamp DESC";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Feedback fb = new Feedback(username,
                            rs.getString("text"),
                            rs.getString("type"));
                    fb.timestamp = rs.getTimestamp("timestamp");
                    feedbackList.add(fb);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error fetching user feedbacks: " + e.getMessage());
        }
        return feedbackList;
    }

    public static boolean insertAnnouncement(String message) {
        String sql = "INSERT INTO announcements (message) VALUES (?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, message);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Error inserting announcement: " + e.getMessage());
            return false;
        }
    }

}
// --- Central data store ---
class MetroDataStore {
    // Singleton instance
    static MetroDataStore instance = new MetroDataStore();
    public static MetroDataStore getInstance() { return instance; }

    // List of all users in the system
    public List<User> users = new ArrayList<>();
    // List of all tickets booked in the system
    public List<Ticket> allTickets = new ArrayList<>();
    // List of all feedback entries submitted by users
    public List<Feedback> feedbacks = new ArrayList<>();
    // Mapping from username to their list of feedback entries
    public Map<String, List<Feedback>> feedbackMap = new HashMap<>();
    // Set of all station names
    public Set<String> stations = new HashSet<>();
    // Mapping station name to StationInfo object (contains details, distances, etc.)
    public Map<String, StationInfo> stationInfoMap = new HashMap<>();
    // List of all support tickets
    public List<SupportTicket> supportTickets = new ArrayList<>();
    // List of system-wide announcements/messages
    public List<String> announcements = new ArrayList<>();

    // Sorted set of users maintained in ascending order by username
    public TreeSet<User> sortedUsers = new TreeSet<>(Comparator.comparing(u -> u.username));

    // Add user to both users list and the sorted set
    public void addUser(User u) {
        users.add(u);
        sortedUsers.add(u);
    }

    // Remove user from both users list and sorted set
    public void removeUser(User u) {
        users.remove(u);
        sortedUsers.remove(u);
    }

    // Add a ticket to the global tickets list
    public void addTicket(Ticket t) {
        allTickets.add(t);
    }

    // Add a feedback entry and map it under the username key
    public void addFeedback(Feedback fb) {
        feedbacks.add(fb);
        feedbackMap.computeIfAbsent(fb.username, k -> new ArrayList<>()).add(fb);
    }
    // Simple generic Queue implementation using linked nodes
    static class MyQueue<T> {
        // Inner class for nodes
        private static class Node<T> {
            T data;
            Node<T> next;
            Node(T data) { this.data = data; }
        }

        private Node<T> head; // dequeue from head
        private Node<T> tail; // enqueue at tail
        private int size = 0;

        // Add element to tail
        public void enqueue(T elem) {
            Node<T> newNode = new Node<>(elem);
            if (tail != null) {
                tail.next = newNode;
            }
            tail = newNode;
            if (head == null) head = tail;
            size++;
        }

        // Remove element from head, return it
        public T dequeue() {
            if (head == null) return null;
            T val = head.data;
            head = head.next;
            if (head == null) tail = null;
            size--;
            return val;
        }

        // Peek head element without dequeue
        public T peek() {
            return head != null ? head.data : null;
        }

        public boolean isEmpty() {
            return size == 0;
        }

        public int size() {
            return size;
        }
    }

}

// Role class holds constant values for user roles in the system.
final class Role {
    // Role for regular users who book tickets and use metro services.
    public static final String USER = "USER";
    // Role for system administrators who manage users, stations, and perform admin functions.
    public static final String ADMIN = "ADMIN";
    // Role for support staff responsible for handling support tickets and user complaints.
    public static final String SUPPORT_STAFF = "SUPPORT_STAFF";
}

// SupportTicketStatus class defines constants for the status of support tickets.
final class SupportTicketStatus {
    // New support ticket that has not yet been addressed.
    public static final String OPEN = "OPEN";
    // Support ticket is being handled/in progress by assigned staff.
    public static final String IN_PROGRESS = "IN_PROGRESS";
    // Support ticket has been resolved and marked as completed.
    public static final String RESOLVED = "RESOLVED";

}
// --- Station info with adjacency graph distances (km) ---
class StationInfo {
    String name; // Name of the station
    String description; // Brief description of the station (purpose, location type, etc.)
    boolean hasRestrooms; // Whether the station has restroom facilities
    boolean hasParking; // Whether the station has parking facilities
    boolean hasWifi; // Whether the station offers WiFi connectivity
    DBManager.StationLocation location; // (Dynamically) Geographical coordinates of the station

    // Map of adjacent station names to their direct distance in kilometers.
    Map<String, Integer> distances = new HashMap<>();

    // Constructor to initialize station details and facilities.
    StationInfo(String name, String desc, boolean restrooms, boolean parking, boolean wifi) {
        this.name = name;
        this.description = desc;
        this.hasRestrooms = restrooms;
        this.hasParking = parking;
        this.hasWifi = wifi;
        this.location = null; // Can be set later
    }

    // Set/update the coordinates of the station.
    public void setLocation(DBManager.StationLocation loc) {
        this.location = loc;
    }

    // Add or update direct distance to another station.
    public void addDistance(String station, int km) {
        distances.put(station, km);
    }

    // String representation: shows name, description, and restroom availability.
    @Override
    public String toString() {
        return String.format("%s (%s) %s", name, description, hasRestrooms ? "[Restrooms]" : "");
    }
}
// --- Base Person with Role ---
abstract class Person {
    String username;   // The username of the person
    String password;   // The hashed password of the person
    String role;       // Role of the person in the system (e.g. USER, ADMIN, SUPPORT_STAFF)

    // Constructor to initialize username, password and role
    Person(String username, String password, String role) {
        this.username = username;
        this.password = password;
        this.role = role;
    }

    // Change the password of the person, after verifying current password
    public void changePassword(String oldPwd, String newPwd) {
        // Hash the old password input for verification
        String oldHashed = MetroTicketBookingSystem.hashPassword(oldPwd);

        // Check if the old password matches the current stored password
        if (!password.equals(oldHashed)) {
            System.out.println("Old password incorrect.");
            return;
        }

        // Hash the new password
        String newHashed = MetroTicketBookingSystem.hashPassword(newPwd);

        // Check if new password is same as old password
        if (newHashed.equals(password)) {
            System.out.println("New password cannot be the same as the old password.");
            return;
        }

        // Update the password
        password = newHashed;

        // Persist the new hashed password for users in the database
        DBManager.updateUserPassword(username, newHashed);

        System.out.println("Password changed successfully.");
        DBManager.insertAuditLog(username, "Changed password");
        MetroTicketBookingSystem.logAudit(username, "Changed password");
    }

}

class MetroCard {
    private int cardNumber;
    private double balance;
    private double minBalanceThreshold = 50; // Auto recharge threshold
    private boolean autoRechargeEnabled = false;
    User owner; // Link to owner for auto recharge

    public MetroCard(int cardNumber, double initialBalance, User owner) {
        this.cardNumber = cardNumber;
        this.balance = initialBalance;
        this.owner = owner;
    }

    public int getCardNumber() { return cardNumber; }
    public double getBalance() { return balance; }
    public void setAutoRecharge(boolean val) {
        autoRechargeEnabled = val;
        // Optionally notify user here
    }
    public boolean isAutoRechargeEnabled() { return autoRechargeEnabled; }
    public double getMinBalanceThreshold() { return minBalanceThreshold; }
    public void setMinBalanceThreshold(double thres) { minBalanceThreshold = thres; }

    // Auto recharge triggers if balance is less than minBalanceThreshold
    private boolean tryAutoRecharge(double needed) {
        if (!autoRechargeEnabled) return false;
        double rechargeAmount = Math.max(minBalanceThreshold * 2, needed);
        if (owner.walletBalance >= rechargeAmount) {
            owner.walletBalance -= rechargeAmount;
            DBManager.updateUserWalletBalance(owner.username, owner.walletBalance);
            balance += rechargeAmount;
            System.out.printf("Auto-recharged Rs. %.2f from wallet to Metro Card #%d. New card balance: Rs. %.2f\n",
                    rechargeAmount, cardNumber, balance);
            MetroTicketBookingSystem.logAudit(owner.username, "Auto recharged MetroCard by Rs. " + rechargeAmount);
            return true;
        }
        return false;
    }

    // Manual recharge of MetroCard
    public void recharge(double amount) {
        if (amount > 0) {
            balance += amount;
            System.out.printf("Metro Card recharged! New Balance: Rs. %.2f\n", balance);
            MetroTicketBookingSystem.logAudit(owner.username, "Manually recharged MetroCard by Rs. " + amount);
        } else {
            System.out.println("Recharge amount should be positive.");
        }
    }

    // Deduct fare from the card balance; use auto recharge if needed
    public boolean deduct(double fare) {
        if (fare <= balance) {
            balance -= fare;
            System.out.printf("Fare deducted: Rs. %.2f. Card Balance: Rs. %.2f\n", fare, balance);
            MetroTicketBookingSystem.logAudit(owner.username, "Fare Rs." + fare + " deducted from MetroCard");
            maybeAutoRecharge();
            return true;
        } else if (tryAutoRecharge(fare - balance)) {
            if (balance >= fare) {
                balance -= fare;
                System.out.printf("Fare deducted after auto recharge: Rs. %.2f. Card Balance: Rs. %.2f\n", fare, balance);
                MetroTicketBookingSystem.logAudit(owner.username, "Fare Rs." + fare + " deducted from MetroCard after auto recharge");
                maybeAutoRecharge();
                return true;
            }
        }
        System.out.println("Insufficient Metro Card balance.");
        return false;
    }

    // Check and trigger auto recharge if balance falls below threshold
    private void maybeAutoRecharge() {
        if (autoRechargeEnabled && balance < minBalanceThreshold) {
            tryAutoRecharge(0);
        }
    }

}
// --- Ticket with scheduled date, cancellable with refund ---
class Ticket {
    // Static counter to generate unique ticket IDs automatically
    static int ticketCounter = 1;

    // Unique identifier for this ticket
    int ticketId;

    // Username of the user who booked the ticket
    String username;

    // Source station name
    String source;

    // Destination station name
    String destination;

    // Number of passengers included in this ticket
    int passengers;

    // Total fare for this ticket
    double fare;

    // Scheduled travel date for this ticket
    Date travelDate;

    // Flag indicating if the ticket is cancelled
    boolean cancelled = false;

    // Date and time when the ticket was booked
    Date bookingDate;

    // Constructor to create a new ticket with all details
    Ticket(String username, String source, String destination, int passengers, double fare, Date travelDate) {
        this.ticketId = ticketCounter++;  // Assign unique ID and increment for next ticket
        this.username = username;
        this.source = source;
        this.destination = destination;
        this.passengers = passengers;
        this.fare = fare;
        this.travelDate = travelDate;
        this.bookingDate = new Date(); // Set booking time to current time
    }

    /**
     * Cancels the ticket, returns the refund amount.
     * Refund rate is 80% if cancelled at least 24 hours before travel, else 50%.
     * Multiple cancellations return zero.
     */
    public double cancel() {
        if (cancelled) return 0; // Already cancelled, no refund
        cancelled = true; // Mark ticket as cancelled

        long diffMs = travelDate.getTime() - new Date().getTime(); // Time difference in milliseconds

        double refundRate = 0.5; // Default refund rate is 50%
        if (diffMs >= 24 * 60 * 60 * 1000) refundRate = 0.8; // If >= 24 hrs, refund 80%
        return fare * refundRate; // Calculate refund amount
    }

    /**
     * Returns a formatted string representing the ticket details.
     * Shows ticket ID, user, route, number of passengers, fare, travel date,
     * and cancellation status.
     */
    @Override
    public String toString() {
        return String.format("Ticket #%d for %s | %s -> %s | Passengers: %d | Fare: Rs. %.2f | Date: %s %s",
                ticketId, username, source, destination, passengers, fare,
                new SimpleDateFormat("yyyy-MM-dd").format(travelDate),
                cancelled ? "[Cancelled]" : "");
    }
}
// --- Feedback linked with SupportTickets ---
class Feedback {
    // The username of the user who submitted this feedback
    String username;

    // The feedback or complaint text content
    String text;

    // The type of feedback: either a "feedback" or a "complaint"
    String type;

    // Timestamp of when the feedback was created
    Date timestamp;

    // Constructor to create a new Feedback with details
    Feedback(String username, String text, String type) {
        this.username = username;
        this.text = text;
        this.type = type;
        this.timestamp = new Date();  // Set current date/time as timestamp
    }
    /**
     * Returns a formatted string representing the Feedback,
     * including timestamp, username, type, and text content.
     */
    @Override
    public String toString() {
        return String.format("[%s] %s (%s): %s", timestamp.toString(), username, type, text);
    }
}
// --- Support Ticket for feedback handling by SupportStaff ---
class SupportTicket {
    // Static counter to generate unique ticket IDs
    static int idCounter = 1;

    // Unique identifier for this support ticket
    int ticketId;

    // The Feedback object linked to this support ticket
    Feedback feedback;

    // Current status of the support ticket: OPEN, IN_PROGRESS, RESOLVED
    String status;

    // Username of the staff member assigned to this ticket (nullable)
    String assignedStaffUsername;

    // Date and time when this ticket was created
    Date createdDate;

    // Date and time when this ticket was resolved (nullable)
    Date resolvedDate;

    // Constructor to create a new support ticket linked to a feedback
    SupportTicket(Feedback fb) {
        this.ticketId = idCounter++; // Assign unique ID and increment
        this.feedback = fb;
        this.status = SupportTicketStatus.OPEN; // Default new tickets are open
        this.assignedStaffUsername = null; // No staff assigned initially
        this.createdDate = new Date(); // Current date-time as creation time
        this.resolvedDate = null; // Not resolved yet
    }

    /**
     * Assigns the ticket to staff and marks it as in progress
     */
    public void assign(String staffUsername) {
        assignedStaffUsername = staffUsername;
        status = SupportTicketStatus.IN_PROGRESS;
    }

    /**
     * Marks the ticket as resolved and records the resolution date.
     */
    public void resolve() {
        status = SupportTicketStatus.RESOLVED;
        resolvedDate = new Date();
    }

    /**
     * Returns a formatted string with ticket details including feedback.
     */
    @Override
    public String toString() {
        return String.format("SupportTicket #%d | User: %s | Status: %s | Assigned To: %s | Created: %s | Resolved: %s\n  Feedback: %s",
                ticketId,
                feedback.username,
                status,
                assignedStaffUsername != null ? assignedStaffUsername : "Unassigned",
                createdDate.toString(),
                resolvedDate != null ? resolvedDate.toString() : "N/A",
                feedback.text);
    }
}

// --- User class ---
class User extends Person {
    double walletBalance;
    MetroCard metroCard;
    List<Ticket> tickets = new ArrayList<>();
    List<SupportTicket> supportTickets = new ArrayList<>();
    List<String> monthlyPasses = new ArrayList<>(); // Store "source->dest" strings for monthly passes

    // Constructor initializing user with username, hashed password and initial wallet balance
    public User(String username, String password, double initialBalance) {
        super(username, password, Role.USER);
        this.walletBalance = initialBalance;
        // MetroCard can be initialized separately after DB insertion
    }

    // Calculate fare based on source, destination, number of passengers, travel date
    public double calculateFare(String source, String destination, int passengers, Date travelDate) {
        // 1. Check if monthly pass exists for either direction
        if(monthlyPasses.isEmpty()) {
            System.out.println("You currently have no active monthly passes.");
        } else {
            System.out.println("Your active monthly passes for routes:");
            monthlyPasses.forEach(route -> System.out.println(" → " + route));
        }

        String key1 = source + "->" + destination;
        String key2 = destination + "->" + source;
        if (monthlyPasses.contains(key1) || monthlyPasses.contains(key2)) {
            System.out.println("Monthly pass active for this route — Rs. 0 fare!");
            System.out.println("No fare deducted for this trip. Your pass was already purchased earlier.");
            return 0.0;
        }

        // 2. Base fare: Rs. 5 per km per passenger calculated with graph distance
        int dist = MetroTicketBookingSystem.getDistanceBetweenStations(source, destination);
        if (dist <= 0) return 0.0; // Invalid or same station

        double baseFare = dist * passengers * 5;

        // 3. Peak or off-peak pricing adjustment based on travel hour
        Calendar cal = Calendar.getInstance();
        cal.setTime(travelDate);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        if ((hour >= 8 && hour <= 10) || (hour >= 17 && hour <= 19)) {
            baseFare *= 1.20; // +20% peak fare
            System.out.println("⏰ Peak hour pricing (+20%) applied");
        } else {
            baseFare *= 0.90; // -10% off-peak discount
            System.out.println("🌙 Off-peak discount (-10%) applied");
        }

        // 4. Special day surcharges/discounts (holiday/event)
        Map<String, Double> eventModifiers = new HashMap<>();
        eventModifiers.put("2025-12-31", 1.25); // +25% surcharge
        eventModifiers.put("2025-08-15", 0.85); // -15% discount
        String dateStr = new SimpleDateFormat("yyyy-MM-dd").format(travelDate);
        if (eventModifiers.containsKey(dateStr)) {
            baseFare *= eventModifiers.get(dateStr);
            System.out.println("📅 Special Day Pricing applied: x" + eventModifiers.get(dateStr));
        }

        // 5. Group booking discount
        if (passengers >= 5 && passengers < 10) {
            baseFare *= 0.95; // 5% discount
            System.out.println("Group booking: 5% discount applied.");
        } else if (passengers >= 10) {
            baseFare *= 0.90; // 10% discount
            System.out.println("Group booking: 10% discount applied.");
        }

        return baseFare;
    }
    private void printMonthlyPassDetails(String sourceCode, String destinationCode,
                                         double price, double walletBalance, Date purchaseDate, Date expiryDate) {
        // ANSI Color codes
        final String RESET = "\u001B[0m";
        final String HEADER = "\u001B[38;5;205m"; // Pink header
        final String BORDER = "\u001B[38;5;70m"; // Green border
        final String LABEL = "\u001B[38;5;221m"; // Yellow
        final String VALUE = "\u001B[38;5;39m"; // Blue
        final String AMOUNT = "\u001B[38;5;83m"; // Green
        final String DATECOL = "\u001B[38;5;123m"; // Cyan
        int BOX_WIDTH = 90; // much wider

        String hBorder = "═".repeat(BOX_WIDTH - 2);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");

        String srcName = MetroTicketBookingSystem.datastore.stationInfoMap.containsKey(sourceCode)
                ? MetroTicketBookingSystem.datastore.stationInfoMap.get(sourceCode).description : "Unknown";
        String dstName = MetroTicketBookingSystem.datastore.stationInfoMap.containsKey(destinationCode)
                ? MetroTicketBookingSystem.datastore.stationInfoMap.get(destinationCode).description : "Unknown";

        // Top border
        System.out.println(BORDER + "╔" + hBorder + "╗" + RESET);

        // Header
        String centered = " MONTHLY PASS DETAILS ";
        int side = (BOX_WIDTH - 2 - centered.length()) / 2;
        System.out.println(BORDER + "║" + " ".repeat(side) + HEADER + centered + RESET +
                " ".repeat(BOX_WIDTH - 2 - centered.length() - side) + BORDER + "║" + RESET);

        // Section Divider
        System.out.println(BORDER + "╠" + hBorder + "╣" + RESET);

        // Route detail
        String routeStr = VALUE + "Route: " + RESET + VALUE + sourceCode.toUpperCase() + RESET + " (" + srcName + ") "
                + BORDER + " -> " + RESET + VALUE + destinationCode.toUpperCase() + RESET + " (" + dstName + ")";
        printBoxLine(routeStr, BOX_WIDTH, BORDER);

        // Divider
        System.out.println(BORDER + "╠" + "─".repeat(BOX_WIDTH - 2) + "╣" + RESET);

        // Other details
        printBoxLine(LABEL + "Price: " + RESET + AMOUNT + String.format("Rs %.2f", price) + RESET, BOX_WIDTH, BORDER);
        printBoxLine(LABEL + "Wallet Balance: " + RESET + AMOUNT + String.format("Rs %.2f", walletBalance) + RESET, BOX_WIDTH, BORDER);
        printBoxLine(LABEL + "Purchase Date: " + RESET + DATECOL + sdf.format(purchaseDate) + RESET, BOX_WIDTH, BORDER);
        printBoxLine(LABEL + "Expiry Date: " + RESET + DATECOL + sdf.format(expiryDate) + RESET, BOX_WIDTH, BORDER);

        // Bottom border
        System.out.println(BORDER + "╚" + hBorder + "╝" + RESET);
    }

    // Helper for line printing, with padding
    public static void printBoxLine(String content, int width, String BORDER) {
        final String RESET = "\u001B[0m";
        int visualLen = stripColorCodes(content).length();
        int pad = width - 2 - visualLen;
        if (pad < 0) pad = 0;
        System.out.println(BORDER + "║" + RESET + content + " ".repeat(pad) + BORDER + "║" + RESET);
    }

    // Helper: strip ANSI codes for length calculation
    public static String stripColorCodes(String input) {
        return input.replaceAll("\\u001B\\[[;\\d]*m", "");
    }

    // Purchase a monthly pass for a specified route
    public void buyMonthlyPass(String source, String destination) {
        // Validate stations
        if (!MetroTicketBookingSystem.isValidStation(source) || !MetroTicketBookingSystem.isValidStation(destination)) {
            System.out.println("Invalid stations.");
            return;
        }
        if (source.equals(destination)) {
            System.out.println("Source and destination stations cannot be the same.");
            return;
        }
        
        String route = source + "->" + destination;

        // Check for duplicate monthly pass
        if (monthlyPasses.contains(route)) {
            System.out.println("You already have an active monthly pass for this route.");
            return;
        }

        double price = calculateFare(source, destination, 1, new Date()) * 20; // 20 rides in a month

        if (walletBalance >= price) {
            walletBalance -= price;
            DBManager.updateUserWalletBalance(username, walletBalance);
            monthlyPasses.add(route);
            System.out.printf("Monthly pass purchased for %s -> %s. Valid for 30 days.\n", source, destination);

            Date purchaseDate = new Date();
            Calendar cal = Calendar.getInstance();
            cal.setTime(purchaseDate);
            cal.add(Calendar.DAY_OF_MONTH, 30);
            Date expiryDate = cal.getTime();

            int passId = DBManager.insertMonthlyPass(username, source, destination, purchaseDate, expiryDate, price);
            if (passId > 0) {
                System.out.println("Monthly pass saved to database. Pass ID: " + passId);
                    printMonthlyPassDetails(source, destination, price, walletBalance, purchaseDate, expiryDate);
            } else {
                System.out.println("❌ Failed to save monthly pass in database.");
            }
        } else {
            System.out.printf("Insufficient balance. Please recharge Rs. %.2f to buy this pass.\n", price - walletBalance);
        }
    }


    // Export journey history to a CSV file for user
    public void exportJourneyHistory() {
        try {
            // Refresh tickets from DB for up-to-date data
            List<Ticket> dbTickets = DBManager.getTicketsByUser(username);

            String fileName = username + "_journey_history.csv";
            try (PrintWriter pw = new PrintWriter(new File(fileName))) {
                pw.println("TicketID,Username,Source,Destination,Passengers,Fare,TravelDate,Status");

                if (dbTickets == null || dbTickets.isEmpty()) {
                    System.out.println("No tickets found — empty file created.");
                } else {
                    for (Ticket t : dbTickets) {
                        String status = t.cancelled ? "Cancelled" : "Active";
                        pw.printf("%d,%s,%s,%s,%d,%.2f,%s,%s%n",
                                t.ticketId,
                                t.username,
                                t.source,
                                t.destination,
                                t.passengers,
                                t.fare,
                                new SimpleDateFormat("yyyy-MM-dd").format(t.travelDate),
                                status);
                    }
                    System.out.println("✅ Journey history exported to: " + fileName);
                }
            } catch (IOException e) {
                System.out.println("❌ Error exporting journey history: " + e.getMessage());
            }
        } catch (Exception ex) {
            System.out.println("Unexpected error during export: " + ex.getMessage());
        }
    }

    // Book a ticket with payment preference for MetroCard or wallet
    public void bookTicket(String source, String destination, int passengers, Date travelDate, boolean preferCard) {
        // Limit single booking passengers to 15
        if (passengers > 15) {
            System.out.println("⚠️ Sorry, you cannot book more than 15 passengers in a single booking.");
            return;
        }
        // Limit active bookings per user to 15
        long activeBookings = tickets.stream().filter(t -> !t.cancelled).count();
        if (activeBookings >= 15) {
            System.out.println("⚠️ Sorry, you can only make up to 15 active bookings. Please cancel existing tickets to book more.");
            return;
        }

        // Validate booking input data
        if (!MetroTicketBookingSystem.isValidStation(source) || !MetroTicketBookingSystem.isValidStation(destination)
                || source.equals(destination) || passengers <= 0) {
            System.out.println("Invalid booking info.");
            return;
        }

        // Check for duplicate bookings on the same route and date (not cancelled)
        List<Ticket> dbTickets = DBManager.getTicketsByUser(this.username);
        boolean alreadyBooked = dbTickets.stream()
                .anyMatch(t -> t.source.equals(source)
                        && t.destination.equals(destination)
                        && t.travelDate.equals(travelDate)
                        && !t.cancelled);
        if (alreadyBooked) {
            System.out.println("You have already booked this journey for the selected date.");
            return;
        }if (!MetroTicketBookingSystem.isValidStation(source) || !MetroTicketBookingSystem.isValidStation(destination)) {
            System.out.println("Invalid source or destination station code.");
            return;
        }

        int dist = MetroTicketBookingSystem.getDistanceBetweenStations(source, destination);
        if (dist <= 0) {  // distance 0 means same station, -1 means no path
            System.out.println("No valid route found between " + source + " and " + destination + ".");
            return;
        }


        double fare = calculateFare(source, destination, passengers, travelDate);
        System.out.printf("Total Fare: Rs. %.2f\n", fare);

        if (preferCard) {
            double cardBal = metroCard.getBalance();

            if (fare <= cardBal) {
                // Full payment from MetroCard
                metroCard.deduct(fare);
                confirmBooking(source, destination, passengers, fare, travelDate);
                return;

            } else if (cardBal == 0) {
                System.out.println("❌ MetroCard has zero balance and cannot be used for this booking.");
                if (walletBalance >= fare) {
                    System.out.println("💡 Tip: You can pay the full fare using your wallet instead.");
                } else {
                    System.out.printf("Please recharge Rs. %.2f to proceed.\n", fare - walletBalance);
                }
            } else if (cardBal > 0) {
                // Partial payment from card and wallet with consideration for auto-recharge
                double neededForFare = fare - cardBal;
                boolean willAutoRecharge = metroCard.isAutoRechargeEnabled() && (cardBal < metroCard.getMinBalanceThreshold());
                double predictedAutoRecharge = 0;
                if (willAutoRecharge) {
                    predictedAutoRecharge = Math.max(
                            metroCard.getMinBalanceThreshold() * 2,
                            0
                    );
                }
                double totalNeededFromWallet = neededForFare + predictedAutoRecharge;

                if (walletBalance >= totalNeededFromWallet) {
                    metroCard.deduct(cardBal);
                    walletBalance -= neededForFare;
                    DBManager.updateUserWalletBalance(username, walletBalance);
                    System.out.printf("Fare split paid: Rs. %.2f MetroCard + Rs. %.2f Wallet\n", cardBal, neededForFare);
                    MetroTicketBookingSystem.logAudit(username, "Fare split payment: " + fare);
                    confirmBooking(source, destination, passengers, fare, travelDate);
                } else {
                    double shortfall = totalNeededFromWallet - walletBalance;
                    System.out.println("❌ Insufficient balance in wallet and MetroCard for split payment. You need Rs. " + String.format("%.2f", shortfall) + " more.");
                    System.out.printf("Please recharge at least Rs. %.2f to proceed.\n", shortfall);
                }
            } else {
                // Not enough funds in both card and wallet
                System.out.println("❌ Insufficient balance in MetroCard and Wallet. Booking not completed.");
                double shortfall = fare - (cardBal + walletBalance);
                if (shortfall > 0) {
                    System.out.printf("Please recharge at least Rs. %.2f to proceed.\n", shortfall);
                }
            }
        } else {
            // Payment using wallet only when card payment not preferred
            if (walletBalance >= fare) {
                walletBalance -= fare;
                DBManager.updateUserWalletBalance(username, walletBalance);
                confirmBooking(source, destination, passengers, fare, travelDate);
            } else {
                System.out.printf("💰 Wallet: Rs. %.2f | MetroCard: Rs. %.2f\n", walletBalance, metroCard.getBalance());
                System.out.printf("Please recharge at least Rs. %.2f to proceed.\n", fare - walletBalance);
            }
        }
    }

    // Confirm completed booking and save ticket data both memory and DB
    private void confirmBooking(String source, String destination, int passengers, double fare, Date travelDate) {
        Ticket t = new Ticket(username, source, destination, passengers, fare, travelDate);
        tickets.add(t);
        MetroTicketBookingSystem.datastore.addTicket(t);

        int newTicketId = DBManager.insertTicket(username, source, destination, passengers, fare, travelDate, false);
        if (newTicketId != -1) {
            t.ticketId = newTicketId;
            MetroCard mc = this.metroCard;

            MetroTicketBookingSystem.printBookingSummary(
                    source, destination, passengers, travelDate,
                    MetroTicketBookingSystem.getDistanceBetweenStations(source, destination),
                    fare, newTicketId, walletBalance, metroCard.getBalance()
            );


        } else {
            System.out.println("Ticket DB insertion failed.");
            return;
        }

        System.out.printf("💰 Wallet: Rs. %.2f | MetroCard: Rs. %.2f\n", walletBalance, metroCard.getBalance());
        MetroTicketBookingSystem.logAudit(username, "Booked ticket #" + t.ticketId);

        // Loyalty reward: Rs. 50 credited every 10 tickets booked
        if (tickets.size() > 0 && tickets.size() % 10 == 0) {
            walletBalance += 50;
            DBManager.updateUserWalletBalance(username, walletBalance);
            System.out.println("🎉 Loyalty Reward: Rs. 50 credited to your wallet!");
        }
    }

    // Cancel a ticket and refund user wallet accordingly
    public void cancelTicket(int ticketId) {
        Ticket ticketToCancel = null;
        for (Ticket t : tickets) {
            if (t.ticketId == ticketId && !t.cancelled) {
                ticketToCancel = t;
                break;
            }
        }
        if (ticketToCancel == null) {
            System.out.println("No valid ticket found.");
            return;
        }
        double refund = ticketToCancel.cancel();
        System.out.printf("Ticket #%d cancelled. Refund: Rs. %.2f processing...\n", ticketToCancel.ticketId, refund);
        walletBalance += refund;
        DBManager.updateUserWalletBalance(username, walletBalance);
        System.out.printf("Refund credited to wallet. New wallet balance: Rs. %.2f\n", walletBalance);
        System.out.printf("💰 Wallet: Rs. %.2f | MetroCard: Rs. %.2f\n", walletBalance, metroCard.getBalance());
        MetroTicketBookingSystem.logAudit(username, "Cancelled ticket #" + ticketToCancel.ticketId + ", refund Rs." + refund);

        if (DBManager.cancelTicket(ticketToCancel.ticketId)) {
            System.out.println("Ticket status set to cancelled in DB.");
        } else {
            System.out.println("DB update failed for ticket cancellation.");
        }
    }

    // Submit feedback or complaint, insert in DB and create support ticket
    public void submitFeedback(String text, String type) {
        Feedback fb = new Feedback(username, text, type);
        MetroTicketBookingSystem.datastore.addFeedback(fb);
        // Insert feedback into DB and get generated ID
        int feedbackId = DBManager.insertFeedbackReturnId(username, text, type);
        if (feedbackId > 0) {
            int supportTicketId = DBManager.insertSupportTicketReturnId(feedbackId, SupportTicketStatus.OPEN);
            if (supportTicketId > 0) {
                SupportTicket st = new SupportTicket(fb);
                st.ticketId = supportTicketId; // IMPORTANT: DB generated ticketId
                supportTickets.add(st);
                MetroTicketBookingSystem.datastore.supportTickets.add(st);
                System.out.println("Your " + type + " is submitted and a support ticket #" + st.ticketId + " generated.");
                DBManager.insertAuditLog(username, "Submitted " + type);
                MetroTicketBookingSystem.logAudit(username, "Submitted " + type);

                // Fetch and display past feedback/complaints from DB:
                System.out.println("\nYour Past Feedback / Complaints:");
                List<Feedback> pastFeedbacks = DBManager.getFeedbacksByUsername(username);
                if (pastFeedbacks.isEmpty()) {
                    System.out.println("No previous feedback or complaints found.");
                } else {
                    for (Feedback f : pastFeedbacks) {
                        System.out.printf("- [%s] %s\n", f.timestamp.toString(), f.text);
                    }
                }
            } else {
                System.out.println("Failed to create support ticket in DB.");
            }
        } else {
            System.out.println("Failed to save feedback in DB.");
        }
    }

    // Show journey statistics including total trips, fares spent, and favorite routes
    public void showJourneyStats() {
        List<Ticket> dbTickets = DBManager.getTicketsByUser(username); // Fetch latest tickets from DB

        if (dbTickets == null || dbTickets.isEmpty()) {
            System.out.println("No journeys found.");
            return;
        }

        int totalTrips = dbTickets.size();
        int pastTrips = 0;
        double totalSpent = 0.0;
        Map<String, Integer> routeCount = new HashMap<>();

        Date today = new Date();

        for (Ticket t : dbTickets) {
            // Count usage of all routes
            String route = t.source + "->" + t.destination;
            routeCount.put(route, routeCount.getOrDefault(route, 0) + 1);

            // Count past trips (including cancelled)
            if (t.travelDate.before(today) || t.cancelled) {
                pastTrips++;
            }

            // Sum fare only for completed (non-cancelled, past) trips
            if (!t.cancelled && !t.travelDate.after(today)) {
                totalSpent += t.fare;
            }
        }

        System.out.println("Journey Statistics:");
        System.out.println("Total Trips: " + totalTrips);
        System.out.println("Past Trips (including cancelled): " + pastTrips);
        System.out.printf("Total Fare Spent on Completed Trips: Rs %.2f\n", totalSpent);

        System.out.println("Top 3 Favorite Routes:");
        routeCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3)
                .forEach(e -> System.out.println(e.getKey() + ": " + e.getValue() + " trips"));
    }


    // View tickets filtered by choice: all, upcoming or past
    public void viewTickets(int choice) {
        List<Ticket> dbTickets = DBManager.getTicketsByUser(this.username);
        if (dbTickets == null || dbTickets.isEmpty()) {
            System.out.println("No tickets booked.");
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        String today = sdf.format(new Date());

        switch (choice) {
            case 1: // All Tickets
                System.out.println("-- All Tickets --");
                dbTickets.forEach(System.out::println);
                break;

            case 2: // Upcoming tickets: date >= today & not cancelled
                System.out.println("-- Upcoming Tickets --");
                boolean hasUpcoming = false;
                for (Ticket t : dbTickets) {
                    String tdate = sdf.format(t.travelDate);
                    if (!t.cancelled && tdate.compareTo(today) >= 0) {
                        System.out.println(t);
                        hasUpcoming = true;
                    }
                }
                if (!hasUpcoming) System.out.println("You have no upcoming tickets.");
                break;

            case 3: // Past tickets: cancelled or date < today
                System.out.println("-- Past Tickets --");
                boolean hasPast = false;
                for (Ticket t : dbTickets) {
                    String tdate = sdf.format(t.travelDate);
                    if (t.cancelled || tdate.compareTo(today) < 0) {
                        System.out.println(t);
                        hasPast = true;
                    }
                }
                if (!hasPast) System.out.println("You have no past tickets.");
                break;

            default:
                System.out.println("Invalid option.");
        }
    }
}


// --- Admin class ---
class Admin extends Person {

    // Constructor initializes admin with preset username and hashed password
    Admin() {
        super("admin", MetroTicketBookingSystem.hashPassword("admin123"), Role.ADMIN);
    }

    // Verify login credentials for admin
    boolean login(String uname, String pwd) {
        String hashed = MetroTicketBookingSystem.hashPassword(pwd);
        return username.equals(uname) && password.equals(hashed);
    }

    // View all tickets in the system
    void viewAllTickets() {
        // Fetch all tickets from the database (not just current user)
        List<Ticket> ticketsFromDB = new ArrayList<>();
        try (Connection conn = DBManager.getConnection()) {
            String sql = "SELECT * FROM tickets ORDER BY bookingDate DESC";
            PreparedStatement ps = conn.prepareStatement(sql);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Ticket t = new Ticket(
                        rs.getString("username"),
                        rs.getString("source"),
                        rs.getString("destination"),
                        rs.getInt("passengers"),
                        rs.getDouble("fare"),
                        rs.getDate("travelDate")
                );
                t.ticketId = rs.getInt("ticketId");
                t.cancelled = rs.getBoolean("cancelled");
                // Optionally set bookingDate
                t.bookingDate = rs.getTimestamp("bookingDate");
                ticketsFromDB.add(t);
            }
        } catch (SQLException e) {
            System.err.println("Error fetching all tickets from DB: " + e.getMessage());
        }

        if (ticketsFromDB.isEmpty()) {
            System.out.println("No tickets in system.");
            return;
        }
        // Display tickets (most recent first)
        for (Ticket t : ticketsFromDB) {
            System.out.println(t);
        }
    }

    // Cancel a user's ticket by ticket ID and refund user wallet
    void cancelUserTicket(String username, int ticketId) {
        Ticket tCancel = null;
        for (Ticket t : MetroTicketBookingSystem.datastore.allTickets) {
            if (t.ticketId == ticketId && t.username.equals(username) && !t.cancelled) {
                tCancel = t;
                break;
            }
        }
        if (tCancel == null) {
            System.out.println("No such open ticket found.");
            return;
        }
        double refund = tCancel.cancel();
        System.out.printf("Admin cancelled ticket #%d. Refund: Rs. %.2f\n", ticketId, refund);
        User u = MetroTicketBookingSystem.getUserByName(username);
        if (u != null) {
            u.walletBalance += refund;
            DBManager.updateUserWalletBalance(u.username, u.walletBalance);
            System.out.printf("Refund credited to user's wallet. New wallet balance: Rs. %.2f\n", u.walletBalance);
            MetroTicketBookingSystem.logAudit("admin", "Cancelled ticket #" + ticketId + ", refunded Rs." + refund);
        }
    }

    // Display all registered users along with wallet and MetroCard balances, and ticket counts
    void viewAllUsers() {
        if (MetroTicketBookingSystem.datastore.users.isEmpty()) {
            System.out.println("No users registered.");
            return;
        }
        System.out.println("Registered Users (sorted):");
        for (User u : MetroTicketBookingSystem.datastore.sortedUsers) {
            MetroCard cardToShow = u.metroCard;
            if (cardToShow == null) {
                // Attempt DB fetch if not already in memory
                cardToShow = DBManager.getMetroCardByUsername(u.username);
            }
            if (cardToShow != null) {
                System.out.printf(
                        "User: %s | Wallet: Rs. %.2f | MetroCard #%d Balance: Rs. %.2f | Tickets: %d, Active: %d\n",
                        u.username,
                        u.walletBalance,
                        cardToShow.getCardNumber(),
                        cardToShow.getBalance(),
                        u.tickets.size(),
                        (int) u.tickets.stream().filter(t -> !t.cancelled).count()
                );
            } else {
                System.out.printf(
                        "User: %s | Wallet: Rs. %.2f | MetroCard: --none-- | Tickets: %d, Active: %d\n",
                        u.username,
                        u.walletBalance,
                        u.tickets.size(),
                        (int) u.tickets.stream().filter(t -> !t.cancelled).count()
                );
            }
        }
    }

    // Remove a user from the system (database and in-memory)
    void removeUser(String uname) {
        boolean dbResult = DBManager.removeUser(uname);
        if (!dbResult) {
            System.out.println("No such user in database, or DB deletion error.");
            return;
        }

        User toRemove = MetroTicketBookingSystem.getUserByName(uname);
        if (toRemove == null) {
            System.out.println("User '" + uname + "' removed from database, but was not found in memory.");
            System.out.println("User " + uname + " was removed by admin.");
            MetroTicketBookingSystem.logAudit("admin", "Removed user " + uname);
            return;
        }

        // Remove related tickets, feedbacks, support tickets from memory and user itself
        MetroTicketBookingSystem.datastore.allTickets.removeIf(t -> t.username.equals(uname));
        MetroTicketBookingSystem.datastore.feedbacks.removeIf(fb -> fb.username.equals(uname));
        MetroTicketBookingSystem.datastore.supportTickets.removeIf(st -> st.feedback.username.equals(uname));
        MetroTicketBookingSystem.datastore.removeUser(toRemove);

        System.out.println("User " + uname + " was removed by admin.");
        MetroTicketBookingSystem.logAudit("admin", "Removed user " + uname);
    }

    // Clear all data in the database (tickets, users, feedbacks, etc.)
    public void clearDatabase() {
        if (DBManager.clearAllData()) {
            System.out.println("✅ All data successfully cleared from database.");
            MetroTicketBookingSystem.logAudit(username, "Cleared all database data");
        } else {
            System.out.println("❌ Failed to clear database data.");
        }
    }

    // Review all current support tickets by printing to console
    void reviewSupportTickets() {
        final String RESET = "\u001B[0m";
        final String HEADER = "\u001B[38;5;205m";
        final String BORDER = "\u001B[38;5;70m";
        final String LABEL = "\u001B[38;5;221m";
        final String VALUE = "\u001B[38;5;39m";

        System.out.println(HEADER + "[ Unassigned Support Tickets (Complaints and Feedback) from DB ]" + RESET);

        int count = 0;
        try (Connection conn = DBManager.getConnection()) {
            String sql =
                    "SELECT st.ticketId, st.status, st.createdDate, fb.username, fb.text, fb.type " +
                            "FROM support_tickets st " +
                            "JOIN feedbacks fb ON st.feedbackId = fb.feedbackId " +
                            "WHERE st.assignedStaffUsername IS NULL " +  // Only unassigned tickets
                            "ORDER BY st.createdDate ASC";
            PreparedStatement ps = conn.prepareStatement(sql);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                count++;
                System.out.println(BORDER + "----------------------------------------" + RESET);
                System.out.println(LABEL + "Ticket ID      : " + VALUE + rs.getInt("ticketId") + RESET);
                System.out.println(LABEL + "Username       : " + VALUE + rs.getString("username") + RESET);
                System.out.println(LABEL + "Type           : " + VALUE + rs.getString("type") + RESET);
                System.out.println(LABEL + "Status         : " + VALUE + rs.getString("status") + RESET);
                System.out.println(LABEL + "Created        : " + VALUE + rs.getTimestamp("createdDate") + RESET);
                System.out.println(LABEL + "Text           : " + VALUE + rs.getString("text") + RESET);
            }
        } catch (SQLException e) {
            System.err.println("Error fetching unassigned support tickets: " + e.getMessage());
        }
        if (count == 0) {
            System.out.println("No unassigned support tickets found in database.");
        }
    }

    // Generate report showing passenger occupancy per station for a given date
    void stationOccupancyReport(Date reportDate) {
        try (Connection conn = DBManager.getConnection()) {
            String dateStr = new SimpleDateFormat("yyyy-MM-dd").format(reportDate);
            String sql = "SELECT source, destination, SUM(passengers) as total_passengers " +
                    "FROM tickets " +
                    "WHERE travelDate = ? AND cancelled = FALSE " +
                    "GROUP BY source, destination";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, dateStr);
            ResultSet rs = ps.executeQuery();

            Map<String, Integer> stationCounts = new HashMap<>();

            while (rs.next()) {
                String source = rs.getString("source");
                String destination = rs.getString("destination");
                int count = rs.getInt("total_passengers");

                stationCounts.put(source, stationCounts.getOrDefault(source, 0) + count);
                stationCounts.put(destination, stationCounts.getOrDefault(destination, 0) + count);
            }

            if (stationCounts.isEmpty()) {
                System.out.println("No bookings found for " + dateStr);
                return;
            }

            System.out.println("Station Occupancy Report for " + dateStr);
            System.out.println("----------------------------------------");
            for (Map.Entry<String, Integer> entry : stationCounts.entrySet()) {
                System.out.printf("Station %s: %d passengers%n", entry.getKey().toUpperCase(), entry.getValue());
            }
        } catch (SQLException e) {
            System.err.println("Error generating occupancy report: " + e.getMessage());
        }
    }

    // Assign a support ticket to a support staff by username
    boolean assignTicket(int ticketId, String staffUsername) {
        try (Connection conn = DBManager.getConnection()) {
            // Check if ticket exists and is OPEN
            String checkSql = "SELECT status FROM support_tickets WHERE ticketId = ?";
            PreparedStatement checkPs = conn.prepareStatement(checkSql);
            checkPs.setInt(1, ticketId);
            ResultSet rs = checkPs.executeQuery();
            if (!rs.next()) {
                System.out.println("Support ticket not found.");
                return false;
            }
            String status = rs.getString("status");
            if (!"OPEN".equals(status)) {
                System.out.println("Only OPEN tickets can be assigned.");
                return false;
            }

            // Verify staff username exists and has SUPPORT_STAFF role
            String staffCheckSql = "SELECT role FROM users WHERE username = ?";
            PreparedStatement staffPs = conn.prepareStatement(staffCheckSql);
            staffPs.setString(1, staffUsername);
            ResultSet staffRs = staffPs.executeQuery();
            if (!staffRs.next() || !"SUPPORT_STAFF".equals(staffRs.getString("role"))) {
                System.out.println("Invalid support staff username.");
                return false;
            }

            // Update assignment and status in DB
            String updateSql = "UPDATE support_tickets SET assignedStaffUsername = ?, status = ? WHERE ticketId = ?";
            PreparedStatement updatePs = conn.prepareStatement(updateSql);
            updatePs.setString(1, staffUsername);
            updatePs.setString(2, "IN_PROGRESS");
            updatePs.setInt(3, ticketId);
            int rows = updatePs.executeUpdate();
            if (rows == 0) {
                System.out.println("Failed to assign support ticket.");
                return false;
            }

            // Update in-memory SupportTicket object
            SupportTicket ticket = null;
            for (SupportTicket st : MetroDataStore.getInstance().supportTickets) {
                if (st.ticketId == ticketId) {
                    ticket = st;
                    break;
                }
            }
            if (ticket != null) {
                ticket.assign(staffUsername);
            }

            System.out.printf("Support ticket #%d assigned to %s successfully.\n", ticketId, staffUsername);
            DBManager.insertAuditLog("admin", "Assigned ticket #" + ticketId + " to " + staffUsername);
            return true;

        } catch (SQLException e) {
            System.out.println("Error during ticket assignment: " + e.getMessage());
            return false;
        }
    }
    // Post a system announcement (maximum 5 latest retained)
    void postAnnouncement(String message) {
        List<String> ann = MetroTicketBookingSystem.datastore.announcements;
        if (ann.size() >= 5) {
            ann.remove(0); // remove oldest
        }
        ann.add(message);
        System.out.println("✅ Announcement posted successfully.");
        MetroTicketBookingSystem.logAudit(username, "Posted new announcement: " + message);
    }
}


// --- Support Staff class ---
class SupportStaff extends Person {
    List<SupportTicket> assignedTickets = new ArrayList<>();

    // Constructor initializes SupportStaff with username, hashed password, and role SUPPORT_STAFF
    SupportStaff(String username, String password) {
        super(username, MetroTicketBookingSystem.hashPassword(password), Role.SUPPORT_STAFF);
    }

    // View all support tickets assigned to this staff member
    void viewAssignedTickets() {
        System.out.println("Fetching assigned tickets...");
        refreshAssignedTicketsFromDB();
        List<SupportTicket> assignedTickets = DBManager.getAssignedTicketsFromDB(this.username);

        if (assignedTickets.isEmpty()) {
            System.out.println("No tickets assigned (past or present).");
        } else {
            for (SupportTicket st : assignedTickets) {
                System.out.printf(
                        "Ticket #%d | User: %s | Status: %s | Created: %s | Resolved: %s\nFeedback: %s\n",
                        st.ticketId,
                        st.feedback.username,
                        st.status,
                        new SimpleDateFormat("yyyy-MM-dd").format(st.createdDate),
                        (st.resolvedDate == null) ? "N/A" : new SimpleDateFormat("yyyy-MM-dd").format(st.resolvedDate),
                        st.feedback.text
                );
            }
        }
    }

    // Update status of an assigned support ticket by ticket ID and new status (OPEN, IN_PROGRESS, RESOLVED)
    void updateTicketStatus(int ticketId, String status) {
        // Fetch the ticket assigned to this staff from DB
        SupportTicket st = DBManager.getSupportTicketByIdAndStaff(ticketId, this.username);
        if (st == null) {
            System.out.println("No such assigned ticket found.");
            return;
        }
        Date resolvedDate = null;
        if (status.equals(SupportTicketStatus.RESOLVED)) {
            st.resolve();
            resolvedDate = st.resolvedDate;
        } else {
            st.status = status;
        }
        // Update DB with new status and resolved date
        boolean updatedInDB = DBManager.updateSupportTicketStatus(ticketId, status, resolvedDate);
        if (!updatedInDB) {
            System.out.println("Database update failed for support ticket status.");
            return;
        }
        System.out.println("Support ticket #" + ticketId + " updated to " + status);
        MetroTicketBookingSystem.logAudit(this.username, "Updated support ticket #" + ticketId + " to " + status);
    }
    void refreshAssignedTicketsFromDB() {
        assignedTickets.clear();
        for (SupportTicket st : MetroTicketBookingSystem.datastore.supportTickets) {
            if (this.username.equals(st.assignedStaffUsername)) {
                assignedTickets.add(st);
            }
        }
    }
}

// --- Main application class ---
class MetroTicketBookingSystem {
    static Scanner sc = new Scanner(System.in);
    static MetroDataStore datastore = MetroDataStore.getInstance();
    static Admin admin = new Admin();
    static List<SupportStaff> supportStaffList = new ArrayList<>();
    static Map<String, Person> allPersons = new HashMap<>(); // All persons: user/admin/support staff
    static List<String> auditLog = new ArrayList<>(); // Audit log
    private static final Map<String, Integer> failedAttempts = new ConcurrentHashMap<>();
    private static final Set<String> lockedUsers = ConcurrentHashMap.newKeySet();
    // Scheduled executor to unlock users after timeout
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private static void setupStations() {
        datastore.stations.addAll(Arrays.asList("a", "b", "c", "d", "e"));
        StationInfo a = new StationInfo("a", "Motera Stadium", true, true, true);
        StationInfo b = new StationInfo("b", "Central Market", false, true, false);
        StationInfo c = new StationInfo("c", "Residential Area", true, false, true);
        StationInfo d = new StationInfo("d", "Commercial District", false, true, true);
        StationInfo e = new StationInfo("e", "University Campus", true, true, false);

        a.addDistance("b", 5); b.addDistance("a", 5);
        b.addDistance("c", 3); c.addDistance("b", 3);
        c.addDistance("d", 7); d.addDistance("c", 7);
        d.addDistance("e", 4); e.addDistance("d", 4);
        a.addDistance("e", 12); e.addDistance("a", 12);

        datastore.stationInfoMap.put("a", a);
        datastore.stationInfoMap.put("b", b);
        datastore.stationInfoMap.put("c", c);
        datastore.stationInfoMap.put("d", d);
        datastore.stationInfoMap.put("e", e);

        DBManager.insertOrUpdateStationLocation("a", 10.5, 20.3);
        DBManager.insertOrUpdateStationLocation("b", 15.0, 25.1);
        DBManager.insertOrUpdateStationLocation("c", 20.2, 35.5);
        DBManager.insertOrUpdateStationLocation("d", 30.7, 40.0);
        DBManager.insertOrUpdateStationLocation("e", 45.5, 50.9);
    }

    public static int getDistanceBetweenStations(String source, String dest) {
        if (!datastore.stations.contains(source) || !datastore.stations.contains(dest)) return -1;
        if (source.equals(dest)) return 0;

        Set<String> visited = new HashSet<>();
        MetroDataStore.MyQueue<String> q = new MetroDataStore.MyQueue<>();
        Map<String, Integer> dist = new HashMap<>();

        q.enqueue(source);
        dist.put(source, 0);
        visited.add(source);

        while (!q.isEmpty()) {
            String curr = q.dequeue();
            int currDist = dist.get(curr);
            StationInfo sInfo = datastore.stationInfoMap.get(curr);

            for (Map.Entry<String, Integer> e : sInfo.distances.entrySet()) {
                String nbr = e.getKey();
                int edgeDist = e.getValue();
                if (!visited.contains(nbr)) {
                    dist.put(nbr, currDist + edgeDist);
                    if (nbr.equals(dest)) return dist.get(nbr);
                    q.enqueue(nbr);
                    visited.add(nbr);
                }
            }
        }
        return -1;
    }


    public static boolean isValidStation(String station) {
        return datastore.stations.contains(station);
    }

    public static User getUserByName(String username) {
        Person p = allPersons.get(username);
        if (p instanceof User) return (User)p;
        return null;
    }

    public static void logAudit(String username, String action) {
        String entry = new Date() + " | " + username + " | " + action;
        auditLog.add(entry);
    }

    public static void showAuditLogs() {
        System.out.println("=== Audit Logs ===");
        for (String log : auditLog) {
            System.out.println(log);
        }
    }

    public static String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Hashing algorithm not found", e);
        }
    }

    public static void printBookingSummary(String src, String dst, int passengers, Date travelDate,
                                           int distance, double fare, int ticketId,
                                           double walletBalance, double metroBalance) {
        // ANSI color codes
        final String RESET = "\u001B[0m";
        final String CYAN = "\u001B[36m";
        final String YELLOW = "\u001B[33m";
        final String GREEN = "\u001B[32m";
        final String MAGENTA = "\u001B[35m";
        final String BLUE = "\u001B[34m";

        // Fetch coordinates from the database
        DBManager.StationLocation srcLoc = DBManager.getStationLocation(src);
        DBManager.StationLocation dstLoc = DBManager.getStationLocation(dst);

        System.out.println("\n" + GREEN + "===================================" + RESET);
        System.out.println(CYAN + "   🎫 Metro Ticket Booked!" + RESET);
        System.out.println(YELLOW + "-----------------------------------" + RESET);
        System.out.printf(YELLOW + "Route:    " + RESET + CYAN + "%s ➔ %s\n" + RESET, src, dst);
        if (srcLoc != null)
            System.out.printf("   - " + BLUE + "%s" + RESET + ": (x=%.2f, y=%.2f)\n", src, srcLoc.x, srcLoc.y);
        if (dstLoc != null)
            System.out.printf("   - " + BLUE + "%s" + RESET + ": (x=%.2f, y=%.2f)\n", dst, dstLoc.x, dstLoc.y);
        System.out.printf(YELLOW + "Date:     " + RESET + MAGENTA + "%s\n" + RESET,
                new SimpleDateFormat("yyyy-MM-dd").format(travelDate));
        System.out.printf(YELLOW + "Pax:      " + RESET + MAGENTA + "%d\n" + RESET, passengers);
        System.out.printf(YELLOW + "Distance: " + RESET + MAGENTA + "%d km\n" + RESET, distance);
        System.out.printf(YELLOW + "Fare:     " + RESET + MAGENTA + "Rs. %.2f\n" + RESET, fare);
        System.out.println(YELLOW + "-----------------------------------" + RESET);
        System.out.printf(YELLOW + "TicketID: " + RESET + MAGENTA + "%d\n" + RESET, ticketId);
        System.out.printf(YELLOW + "Wallet:   " + RESET + MAGENTA + "Rs. %.2f\n" + RESET, walletBalance);
        System.out.printf(YELLOW + "Card:     " + RESET + MAGENTA + "Rs. %.2f\n" + RESET, metroBalance);
        System.out.println(GREEN + "===================================" + RESET + "\n");
    }

    public static void main(String[] args) {
        DBManager.setupDatabase();
        setupStations();

        // Create admin and 2 support staff for demo
        allPersons.put(admin.username, admin);
        SupportStaff ss1 = new SupportStaff("support1", "supp123");
        supportStaffList.add(ss1);
        allPersons.put(ss1.username, ss1);

        SupportStaff ss2 = new SupportStaff("support2", "supp456");
        supportStaffList.add(ss2);
        allPersons.put(ss2.username, ss2);
        // Ensure support staff exists in database for foreign key assignments
        if (!DBManager.usernameExists("support1")) {
            DBManager.insertUser("support1", MetroTicketBookingSystem.hashPassword("supp123"), 0.0, Role.SUPPORT_STAFF);
        }
        if (!DBManager.usernameExists("support2")) {
            DBManager.insertUser("support2", MetroTicketBookingSystem.hashPassword("supp456"), 0.0, Role.SUPPORT_STAFF);
        }
        // LOAD ALL EXISTING USERS INTO MEMORY ON STARTUP
        List<User> usersFromDB = DBManager.getAllUsers();
        for (User u : usersFromDB) {
            MetroCard card = DBManager.getMetroCardByUsername(u.username);
            if (card != null) {
                card.owner = u;
                u.metroCard = card;
            }
            datastore.addUser(u);
            allPersons.put(u.username, u);
            //  load user's MetroCard, tickets, passes from DB for better UX
        }

        while (true) {
            System.out.println("\n==============================================");
            System.out.println("🌟 Welcome to the Metro Ticket Booking System 🌟");
            System.out.println("==============================================");
            System.out.println("Please select an option to continue:");
            System.out.println("----------------------------------------------");
            System.out.println("1️⃣  Register as a New User");
            System.out.println("2️⃣  Login (User / Admin / Support Staff)");
            System.out.println("3️⃣  Exit");
            System.out.println("----------------------------------------------");
            System.out.print("👉 Enter your choice (1-3): ");

            int ch = safeInt();
            switch (ch) {
                case 1: registerUser(); break;
                case 2: login(); break;
                case 3:
                    System.out.println("Thank you! Goodbye.");
                    return;
                default: System.out.println("Invalid option.");
            }
        }
    }
    private static final double MAX_INITIAL_BALANCE = 5000.0;

    private static void registerUser() {
        System.out.print("Enter username: ");
        String uname = sc.nextLine().trim().toLowerCase();
// Check username validity
        if (!isValidUsername(uname)) {
            System.out.println("❌ Username must be 3-10 chars, start with a letter, and contain only lowercase letters and digits.");
            return;
        }
        Set<String> reservedUsernames = new HashSet<>(Arrays.asList("admin", "support1", "support2"));
        if (reservedUsernames.contains(uname)) {
            System.out.println("❌ The username '" + uname + "' is reserved and cannot be used.");
            return;
        }

        if (DBManager.usernameExists(uname)) {
            System.out.println("❌ Username already exists in DB.");
            return;
        }

        System.out.print("Enter password: ");
        String pwd = sc.nextLine();
        // Password validity check
        if (!isValidPassword(pwd)) {
            System.out.println("❌ Password must be 3-10 chars long and contain no spaces.");
            return;
        }

        double bal = -1;
        while (bal < 0 || bal > MAX_INITIAL_BALANCE) {
            System.out.print("Enter initial wallet balance (max Rs. " + MAX_INITIAL_BALANCE + "): ");
            bal = safeDouble();

            if (bal == -1) {
                System.out.println("❌ Please enter a valid numerical amount.");
            } else if (bal < 0) {
                System.out.println("❌ Balance must be positive.");
            } else if (bal > MAX_INITIAL_BALANCE) {
                System.out.printf("❌ Initial wallet balance cannot exceed Rs. %.2f. Please enter a valid amount.\n", MAX_INITIAL_BALANCE);
            }
        }

        if (DBManager.insertUser(uname, hashPassword(pwd), bal, Role.USER)) {
            int cardNum = DBManager.insertMetroCard(uname, 50.0, false, 50.0);
            User u = new User(uname, hashPassword(pwd), bal);
            if (cardNum != -1) {
                u.metroCard = new MetroCard(cardNum, 50.0, u);
                System.out.println("✅ MetroCard created for " + uname + ". Card Number = " + cardNum);
            } else {
                System.out.println("❌ Failed to create MetroCard for " + uname);
            }
            datastore.addUser(u);
            allPersons.put(uname, u);

            DBManager.insertAuditLog(uname, "Registered");
            logAudit(uname,"Registered");
            System.out.println("✅ User registered successfully!");
        } else {
            System.out.println("❌ Failed to register user (duplicate or DB error).");
        }
    }
    private static boolean isValidUsername(String username) {
        if (username.length() < 3 || username.length() > 10) return false;
        if (!Character.isLetter(username.charAt(0))) return false;
        for (char ch : username.toCharArray()) {
            if (!Character.isLowerCase(ch) && !Character.isDigit(ch)) return false;
        }
        return true;
    }

    private static boolean isValidPassword(String password) {
        if (password.length() < 3 || password.length() > 10) return false;
        if (password.contains(" ")) return false;
        return true;
    }
    private static void login() {
        System.out.print("Username: ");
        String uname = sc.nextLine().trim().toLowerCase();

        if (lockedUsers.contains(uname)) {
            System.out.println("Account locked due to multiple failed login attempts. Please wait 15 seconds before retrying.");
            return;
        }
        System.out.print("Password: ");
        String pwd = sc.nextLine();
        String hashedPwd = hashPassword(pwd);
        Person p = allPersons.get(uname);

        if (p == null || !p.password.equals(hashedPwd)) {
            // Handle failed login
            int attempts = failedAttempts.getOrDefault(uname, 0) + 1;
            failedAttempts.put(uname, attempts);

            if (attempts >= 3) {
                System.out.println("Too many failed attempts. Account locked for 15 seconds.");
                lockedUsers.add(uname);

                // Schedule unlock after 15 seconds
                scheduler.schedule(() -> {
                    failedAttempts.put(uname, 0);
                    lockedUsers.remove(uname);
                    System.out.println("User " + uname + " can attempt to login again now.");
                }, 15, TimeUnit.SECONDS);
            } else {
                System.out.printf("Invalid credentials. Attempt %d of 3.\n", attempts);
            }
            return;
        }
        // Successful login resets failed attempts for that user
        failedAttempts.put(uname, 0);

        // Proceed with the existing login flow here
        logAudit(uname,"Logged in");
        DBManager.insertAuditLog(uname, "Logged in");
        showUserLastAction(uname);
        // ==== Load MetroCard from DB and connect to User ====
        if (p instanceof User) {
            User u = (User)p;
            MetroCard dbCard = DBManager.getMetroCardByUsername(uname);
            if (dbCard != null) {
                dbCard.owner = u;        // Set the owner in the card object
                u.metroCard = dbCard;    // Assign the MetroCard with DB cardNumber to the User
            } else {
                System.out.println("No MetroCard record found in database!");
            }
            //MetroCard card = DBManager.getMetroCardByUsername(u.username);
            MetroCard card = DBManager.getMetroCardByUsername(u.username);
            if (card != null) {
                card.owner = u;
                u.metroCard = card;
            }
            u.monthlyPasses.clear();
            // Fetch active monthly passes
            List<String> dbPasses = DBManager.getMonthlyPassRoutesByUsername(u.username);
            Set<String> uniquePasses = new LinkedHashSet<>();

            for (String pass : dbPasses) {
                String[] parts = pass.split("->");
                if (parts.length == 2
                        && MetroTicketBookingSystem.isValidStation(parts[0])
                        && MetroTicketBookingSystem.isValidStation(parts[1])) {
                    uniquePasses.add(pass);
                } else {
                    System.out.println("Route '" + pass + "' is currently under construction or invalid.");
                }
            }

          //  u.monthlyPasses.clear();
            u.monthlyPasses.addAll(uniquePasses);

            if (card != null) {
                System.out.printf("Auto recharge is currently %s.\n",
                        card.isAutoRechargeEnabled() ? "enabled" : "disabled");} else {
                System.out.println("No MetroCard record found in database!");
            } userMenu(u); return;
        } else if (p instanceof Admin) {
            adminMenu((Admin) p); return;
        } else if (p instanceof SupportStaff) {
            supportMenu((SupportStaff) p); return;
        }
        switch(p.role) {
            case Role.USER:
                // ✅ Show latest announcements before going to user menu
                if (!datastore.announcements.isEmpty()) {
                    System.out.println("\n" + "📢".repeat(3) + " Latest Announcements " + "📢".repeat(3));
                    datastore.announcements.stream()
                            .skip(Math.max(0, datastore.announcements.size() - 3))
                            .forEach(msg -> System.out.println(" - " + msg));
                    System.out.println("=".repeat(40));
                }


                userMenu((User)p);
                break;

            case Role.ADMIN:
                adminMenu((Admin)p);
                break;

            case Role.SUPPORT_STAFF:
                supportMenu((SupportStaff)p);
                break;
        }

    }
    public static void showUserLastAction(String username) {
        String latestLog = DBManager.getLatestAuditLog(username);
        System.out.println("Latest user action: " + (latestLog != null ? latestLog : "No logs found"));
    }
    private static void printMetroCardDetails(MetroCard card) {
        if (card == null) {
            System.out.println("No MetroCard record found in the database!");
            return;
        }

        // ANSI color codes
        final String RESET = "\u001B[0m";
        final String BORDER = "\u001B[38;5;33m";   // Blue border
        final String HEADER = "\u001B[38;5;205m";  // Pink/Magenta for header
        final String LABEL = "\u001B[38;5;221m";   // Yellow for labels
        final String VALUE = "\u001B[38;5;83m";    // Green for values
        final String TITLE = "\u001B[38;5;123m";   // Blue/Cyan for box title
        final String FOOTER = "\u001B[38;5;246m";  // Light gray for footer

        int width = 56;
        String horizontalBorder = BORDER + "┌" + "─".repeat(width) + "┐" + RESET;
        String horizontalBottom = BORDER + "└" + "─".repeat(width) + "┘" + RESET;
        String emptyLine = BORDER + "│" + RESET + " ".repeat(width) + BORDER + "│" + RESET;

        // For perfect padding (right-align Card No.)
        String cardNoStr = String.format("Card No. %d", card.getCardNumber());
        String cardNoLine = BORDER + "│" + RESET +
                " ".repeat(width - cardNoStr.length()) + VALUE + cardNoStr + RESET +
                BORDER + "│" + RESET;

        // Centered text helper
        java.util.function.Function<String, String> centerLine = s -> {
            int totalPad = width - stripColorCodes(s).length();
            int left = totalPad / 2, right = totalPad - left;
            return BORDER + "│" + RESET + " ".repeat(left) + s + " ".repeat(right) + BORDER + "│" + RESET;
        };

        // Left-padded detail lines
        java.util.function.Function<String, String> detailLine = s -> {
            String content = "    " + s;
            int pad = width - stripColorCodes(content).length();
            return BORDER + "│" + RESET + content + " ".repeat(pad) + BORDER + "│" + RESET;
        };

        // Compose and print the box
        System.out.println(horizontalBorder);
        System.out.println(cardNoLine);
        System.out.println(emptyLine);
        System.out.println(centerLine.apply(TITLE + "METRO CARD DETAILS" + RESET));
        System.out.println(emptyLine);

        System.out.println(detailLine.apply(LABEL + "Balance: " + RESET + VALUE + String.format("Rs. %.2f", card.getBalance()) + RESET));
        System.out.println(detailLine.apply(LABEL + "Auto Recharge: " + RESET + VALUE + (card.isAutoRechargeEnabled() ? "Enabled" : "Disabled") + RESET));
        System.out.println(detailLine.apply(LABEL + "Min Recharge Threshold: " + RESET + VALUE + String.format("Rs. %.2f", card.getMinBalanceThreshold()) + RESET));
        System.out.println(emptyLine);

        System.out.println(centerLine.apply(FOOTER + "— Powered by Metro System —" + RESET));
        System.out.println(horizontalBottom);
    }

    // Helper to strip ANSI color codes for accurate padding
    public static String stripColorCodes(String input) {
        return input.replaceAll("\\u001B\\[[;\\d]*m", "");
    }

    private static void userMenu(User u) {
        while (true) {
            System.out.println("\n" + "=".repeat(48));
            System.out.printf("👤  %s — %s%n", u.username, "User Menu");
            System.out.println("=".repeat(48));
            System.out.println("🚆 1. Book Ticket");
            System.out.println("❌ 2. Cancel Ticket");
            System.out.println("🎫 3. View My Tickets");
            System.out.println("📊 4. View Journey Statistics");
            System.out.println("🗓️ 5. Buy Monthly Pass");
            System.out.println("📄 6. Export My Journey History");
            System.out.println("🏙️ 7. View Station Info");
            System.out.println("💸 8. Recharge Wallet");
            System.out.println("💳 9. Recharge MetroCard");
            System.out.println("🔂 10. Set MetroCard Auto Recharge");
            System.out.println("🔒 11. Change Password");
            System.out.println("💬 12. Submit Feedback / Complaint");
            System.out.println("💳 13. View MetroCard Details");
            System.out.println("📅 14. View My Active Monthly Passes");
            System.out.println("🔍 15. Show Last Action");
            System.out.println("↩️ 16. Logout");
            System.out.println("-".repeat(48));
            System.out.print("👉 Enter your choice (1-16): ");

            int ch = safeInt();
            try {
                switch (ch) {
                    case 1:
                        bookTicketFlow(u);
                        break;
                    case 2:
                        System.out.print("Enter Ticket ID to cancel: ");
                        int tid = safeInt();
                        u.cancelTicket(tid);
                        break;
                    case 3:
                        System.out.println("1. View All Tickets");
                        System.out.println("2. View Upcoming Tickets");
                        System.out.println("3. View Past Tickets");
                        System.out.print("Enter your choice: ");
                        int ticketViewChoice = safeInt();
                        u.viewTickets(ticketViewChoice);
                        break;
                    case 4:
                        u.showJourneyStats();
                        break;
                    case 5:
                        System.out.println("Available Stations:");
                        for (String code : datastore.stations) {
                            StationInfo info = datastore.stationInfoMap.get(code);
                            if (info != null) {
                                System.out.printf(" %s : %s%n", code.toUpperCase(), info.description);
                            }
                        }
                        System.out.print("Enter source station code: ");
                        String src = sc.nextLine().toLowerCase();
                        System.out.print("Enter destination station code: ");
                        String dst = sc.nextLine().toLowerCase();
                        u.buyMonthlyPass(src, dst);
                        break;

                    case 6:
                        u.exportJourneyHistory();
                        break;
                    case 7:
                        viewStationInfo();
                        break;
                    case 8:
                        System.out.print("Enter amount to recharge wallet: ");
                        double amtW = safeDouble();
                        if (amtW <= 0) {
                            System.out.println("Invalid amount.");
                        } else if (amtW > 5000) {
                            System.out.println("Recharge amount cannot exceed Rs. 5000.");
                        } else {
                            u.walletBalance += amtW;
                            DBManager.updateUserWalletBalance(u.username, u.walletBalance);
                            DBManager.insertAuditLog(u.username, "Wallet recharged Rs." + amtW);
                            System.out.printf("Wallet recharged. New balance: Rs. %.2f\n", u.walletBalance);
                            System.out.printf("💰 Wallet: Rs. %.2f | MetroCard: Rs. %.2f\n", u.walletBalance, u.metroCard.getBalance());
                            MetroTicketBookingSystem.logAudit(u.username, "Wallet recharged Rs." + amtW);
                        }

                        break;
                    case 9:
                        MetroCard card = DBManager.getMetroCardByUsername(u.username);
                        if (card != null) {
                            card.owner = u;
                            u.metroCard = card;
                        }

                        System.out.print("Enter amount to recharge MetroCard: ");
                        double amtM = safeDouble();
                        if (amtM <= 0) {
                            System.out.println("Invalid amount.");
                        } else if (amtM > 5000) {
                            System.out.println("Recharge amount cannot exceed Rs. 5000.");
                        } else {
                            u.metroCard.recharge(amtM); // this changes in memory
                            // Now update in DB:
                            boolean updated = DBManager.updateMetroCard(
                                    u.metroCard.getCardNumber(), // cardNumber
                                    u.metroCard.getBalance(), // new balance
                                    u.metroCard.isAutoRechargeEnabled(), // autoRecharge setting
                                    u.metroCard.getMinBalanceThreshold() // min balance threshold
                            );
                            if (updated) {
                                DBManager.insertAuditLog(u.username, "MetroCard recharged Rs." + amtM);
                                System.out.println("MetroCard updated in database!");
                            } else {
                                System.out.println("❌ Failed to update MetroCard in database.");
                            }
                        }
                        System.out.printf("💰 Wallet: Rs. %.2f | MetroCard: Rs. %.2f\n", u.walletBalance, u.metroCard.getBalance());
                        if (card != null) {
                            System.out.printf("MetroCard: Card No. %d | Balance: Rs. %.2f | AutoRecharge: %b | Min Threshold: Rs. %.2f\n",
                                    card.getCardNumber(), card.getBalance(), card.isAutoRechargeEnabled(), card.getMinBalanceThreshold());
                        } else {
                            System.out.println("No MetroCard record found in database!");
                        }
                        break;
                    case 10:
                        MetroCard card1 = DBManager.getMetroCardByUsername(u.username);
                        if (card1 != null) {
                            card1.owner = u;
                            u.metroCard = card1;

                            String ans = "";
                            do {
                                System.out.print("Enable auto recharge on MetroCard? (yes/no): ");
                                ans = sc.nextLine().trim().toLowerCase();
                                if (!ans.equals("yes") && !ans.equals("no")) {
                                    System.out.println("❌ Invalid input, please type 'yes' or 'no' exactly.");
                                }
                            } while (!ans.equals("yes") && !ans.equals("no"));

                            u.metroCard.setAutoRecharge(ans.equals("yes")); // in memory
                            // Persist new auto-recharge setting to DB
                            boolean updated1 = DBManager.updateMetroCard(
                                    u.metroCard.getCardNumber(),
                                    u.metroCard.getBalance(),
                                    u.metroCard.isAutoRechargeEnabled(),
                                    u.metroCard.getMinBalanceThreshold()
                            );
                            if (updated1) {
                                System.out.println("Auto recharge setting updated in database!");
                            } else {
                                System.out.println("❌ Failed to update auto recharge in database.");
                            }

                            System.out.printf("MetroCard: Card No. %d | Balance: Rs. %.2f | AutoRecharge: %b | Min Threshold: Rs. %.2f\n",
                                    card1.getCardNumber(), card1.getBalance(), card1.isAutoRechargeEnabled(), card1.getMinBalanceThreshold());
                        } else {
                            System.out.println("No MetroCard record found in database!");
                        }
                        break;

                    case 11:
                        System.out.print("Enter old password: ");
                        String oldp = sc.nextLine();
                        System.out.print("Enter new password: ");
                        String newp = sc.nextLine();
                        u.changePassword(oldp, newp);showUserLastAction(u.username);
                        break;
                    case 12:
                        // Menu case 12: Submit Feedback / Complaint
                        System.out.print("Enter text: ");
                        String text = sc.nextLine();

                        String type = "";
                        while (true) {
                            System.out.print("Type (feedback/complaint): ");
                            type = sc.nextLine().trim().toLowerCase();
                            // Accept only strict "feedback" or "complaint"
                            if (type.equals("feedback") || type.equals("complaint")) {
                                break;
                            }
                            System.out.println("❌ Please type 'feedback' or 'complaint' exactly. Try again.");
                        }

                        u.submitFeedback(text, type);
                    break;
                    case 13:
                       // MetroCard card2 = DBManager.getMetroCardByUsername(u.username);

                        MetroCard card2 = DBManager.getMetroCardByUsername(u.username);
                        if (card2 != null) {
                            card2.owner = u;
                            u.metroCard = card2;
                            printMetroCardDetails(card2);
                        } else {
                            System.out.println("No MetroCard record found in database!");
                        }

                    break;
                    case 14:
                        List<String> passes = DBManager.getMonthlyPassRoutesByUsername(u.username);
                        Set<String> uniqueValidPasses = new LinkedHashSet<>();
                        for (String route : passes) {
                            String[] stations = route.split("->");
                            if (stations.length == 2
                                    && MetroTicketBookingSystem.isValidStation(stations[0].trim())
                                    && MetroTicketBookingSystem.isValidStation(stations[1].trim())) {
                                uniqueValidPasses.add(route);
                            }
                        }
                        if (uniqueValidPasses.isEmpty()) {
                            System.out.println("You have no active valid monthly passes.");
                        } else {
                            System.out.println("Your active monthly passes:");
                            for (String route : uniqueValidPasses) {
                                System.out.println(" - " + route);
                            }
                        }

                        break;
                    case 15:
                        showUserLastAction(u.username);
                        break;

                    case 16:
                        System.out.println("Logging out...");
                        logAudit(u.username, "Logged out");
                        return;

                    default:
                        System.out.println("Invalid choice.");
                }
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
    }

    private static void bookTicketFlow(User u) {
        try {
            // Refresh station list from DB (if needed)
            datastore.stations = DBManager.getAllStationNames();
            // Display available stations with codes and full descriptive names
            System.out.println("Available Stations:");
            for (String code : datastore.stations) {
                StationInfo info = datastore.stationInfoMap.get(code);
                if (info != null) {
                    System.out.printf("%s : %s\n", code, info.description);  // shows code and full name
                }
            }
           // System.out.println("Stations: " + datastore.stations);
            System.out.print("Source station (enter code): ");
            String src = sc.nextLine().toLowerCase();
            System.out.print("Destination station (enter code): ");
            String dst = sc.nextLine().toLowerCase();
            System.out.print("Number of passengers: ");
            int p = safeInt();

            Date travelDate = null;
            while (travelDate == null) {
                System.out.print("Travel date (yyyy-MM-dd), leave blank for today: ");
                String dateStr = sc.nextLine();
                if (dateStr.trim().isEmpty()) {
                    travelDate = new Date(); // today
                } else {
                    try {
                        travelDate = new SimpleDateFormat("yyyy-MM-dd").parse(dateStr);
                        Date now = new Date();
                        // Ignore time, compare only dates
                        String todayStr = new SimpleDateFormat("yyyy-MM-dd").format(now);
                        String travelStr = new SimpleDateFormat("yyyy-MM-dd").format(travelDate);
                        if (travelStr.compareTo(todayStr) < 0) {
                            System.out.println("Travel date must be today or future date. Please enter again.");
                            travelDate = null; // invalid date; loop continues
                        }
                    } catch (ParseException e) {
                        System.out.println("Invalid date format. Please enter date in yyyy-MM-dd format.");
                        travelDate = null; // invalid date; loop continues
                    }
                }
            }

            System.out.print("Pay with MetroCard? (yes/no): ");
            String answer = "";
            while (true) {
                answer = sc.nextLine().trim().toLowerCase();
                if (answer.equals("yes") || answer.equals("no")) {
                    break;
                } else {
                    System.out.println("❌ Invalid input. Please type 'yes' or 'no' exactly.");
                    System.out.print("Pay with MetroCard? (yes/no): ");
                }
            }
            boolean preferCard = answer.equals("yes");
            u.bookTicket(src, dst, p, travelDate, preferCard);

        } catch (Exception ex) {
            System.out.println("An error occurred. Please try booking again.");
        }
    }

    private static void adminMenu(Admin adminUser) {
        while (true) {
            System.out.println("\n" + "=".repeat(48));
            System.out.println("🛡️  Admin Menu");
            System.out.println("=".repeat(48));
            System.out.println("🎫 1. View All Tickets");
            System.out.println("❌ 2. Cancel User Ticket");
            System.out.println("👥 3. View All Users");
            System.out.println("🗑️ 4. Remove User");
            System.out.println("🛠️ 5. Review Support Tickets");
            System.out.println("🔀 6. Assign Support Ticket");
            System.out.println("📈 7. Show Analytics");
            System.out.println("📃 8. View Audit Logs");
            System.out.println("🏢 9. Station Occupancy Report");
            System.out.println("📢 10. Post Announcement");
            System.out.println("🏙️ 11. Update/Add Station Location");
            System.out.println("🏙️ 12. Update Station Description and Facilities");
            System.out.println("💾 13. Backup/Save Current Stations List");
            System.out.println("🕒 14. Rollback/Restore Stations List From Backup");
            System.out.println("🧹 15. Clear All Data (Reset Database)");
            System.out.println("↩️ 16. Logout");
            System.out.println("-".repeat(48));
            System.out.print("👉 Enter your choice (1-16): ");


            System.out.print("Choose: ");
            int ch = safeInt();
            switch (ch) {
                case 1: adminUser.viewAllTickets(); break;
                case 2:
                    System.out.print("Enter username: ");
                    String uname = sc.nextLine();
                    System.out.print("Enter Ticket ID to cancel: ");
                    int tid = safeInt();
                    adminUser.cancelUserTicket(uname, tid);
                    break;
                case 3: adminUser.viewAllUsers(); break;
                case 4:
                    System.out.print("Enter username to remove: ");
                    String rmuser = sc.nextLine();
                    adminUser.removeUser(rmuser);
                    DBManager.insertAuditLog(admin.username, "Description of action");
                    break;
                case 5:
                    adminUser.reviewSupportTickets();
                    break;
                case 6:
                    System.out.print("Enter Support Ticket ID: ");
                    int stid = safeInt();
                    System.out.print("Enter Support Staff username: ");
                    String staffu = sc.nextLine();
                    adminUser.assignTicket(stid, staffu);
                    DBManager.insertAuditLog(admin.username, "Description of action");
                    break;
                case 7:
                    // Show list of all users before prompt
                    System.out.println("Registered users:");
                    if (MetroTicketBookingSystem.datastore.users.isEmpty()) {
                        System.out.println("No users in the system.");
                        break;
                    }
                    for (User user : MetroTicketBookingSystem.datastore.users) {
                        System.out.println(" - " + user.username);
                    }

                    System.out.print("Enter username to analyze: ");
                    String uname1 = sc.nextLine().trim().toLowerCase();

                    // Check if user exists in memory
                    User userForAnalytics = MetroTicketBookingSystem.getUserByName(uname1);
                    if (userForAnalytics == null) {
                        System.out.println("❌ No such user found: " + uname1);
                        break;
                    }

                    List<Ticket> dbTickets = DBManager.getTicketsByUser(uname1);
                    if (dbTickets == null || dbTickets.isEmpty()) {
                        System.out.println("No journey data yet for: " + uname1);
                        break;
                    }

                    Map<String, Integer> stationUsage = new HashMap<>();
                    Map<String, Integer> routes = new HashMap<>();
                    for (Ticket t : dbTickets) {
                        if (!t.cancelled) {
                            stationUsage.put(t.source, stationUsage.getOrDefault(t.source, 0) + t.passengers);
                            stationUsage.put(t.destination, stationUsage.getOrDefault(t.destination, 0) + t.passengers);
                            String route = t.source + "->" + t.destination;
                            routes.put(route, routes.getOrDefault(route, 0) + t.passengers);
                        }
                    }
                    if (stationUsage.isEmpty()) {
                        System.out.println("No journey data for this user.");
                        break;
                    }
                    stationUsage.entrySet().stream()
                            .max(Map.Entry.comparingByValue())
                            .ifPresent(e -> System.out.println(uname1 + "'s busiest station: " + e.getKey() + " with " + e.getValue() + " passengers"));
                    routes.entrySet().stream()
                            .max(Map.Entry.comparingByValue())
                            .ifPresent(e -> System.out.println(uname1 + "'s most popular route: " + e.getKey() + " with " + e.getValue() + " passengers"));
                    break;

                case 8:
                    showAuditLogs();
                    break;
                case 9:
                    try {
                        System.out.print("Enter date (yyyy-MM-dd): ");
                        Date reportDate = new SimpleDateFormat("yyyy-MM-dd").parse(sc.nextLine());
                        adminUser.stationOccupancyReport(reportDate);
                    } catch (Exception e) {
                        System.out.println("Invalid date format.");
                    }
                    break;
                case 10:
                    System.out.print("Enter announcement text: ");
                    String msg = sc.nextLine();
                    if (DBManager.insertAnnouncement(msg)) {
                        adminUser.postAnnouncement(msg);
                        DBManager.insertAuditLog(admin.username, "Posted new announcement: " + msg);
                        System.out.println("✅ Announcement posted and saved in database.");
                    } else {
                        System.out.println("❌ Failed to save announcement in database.");
                    }
                    break;
                case 11:
                    System.out.print("Enter station name: ");
                    String sName = sc.nextLine().trim().toLowerCase();
                    System.out.print("Enter x coordinate: ");
                    double x = safeDouble();
                    System.out.print("Enter y coordinate: ");
                    double y = safeDouble();
                    boolean result = DBManager.insertOrUpdateStationLocation(sName, x, y);

                    if (result) {
                        System.out.println("Station location saved.");

                        // 1. Refresh all stations from the database — BEST PRACTICE!
                        MetroTicketBookingSystem.datastore.stations = DBManager.getAllStationNames();
                        System.out.println("In-memory station list reloaded from the database!");

                        // 2. Update or create StationInfo for this station (ensure info and coordinates are always present)
                        StationInfo info = MetroTicketBookingSystem.datastore.stationInfoMap.get(sName);
                        if (info == null) {
                            info = new StationInfo(
                                    sName,
                                    "Description not set yet", // You can later let admin provide better info
                                    false, // hasRestrooms default
                                    false, // hasParking default
                                    false  // hasWifi default
                            );
                            MetroTicketBookingSystem.datastore.stationInfoMap.put(sName, info);
                            System.out.println("New StationInfo created and added!");
                        }

                        // 3. Update location coordinates if available
                        DBManager.StationLocation loc = DBManager.getStationLocation(sName);
                        if (loc != null) {
                            info.setLocation(loc);
                            System.out.println("StationInfo location updated.");
                        }

                        System.out.print("Do you want to add adjacency distances for this station now? (yes/no): ");
                        String ans = sc.nextLine().trim().toLowerCase();

                        if (ans.equals("yes")) {
                            while (true) {
                                System.out.print("Enter adjacent station code (or leave blank to stop): ");
                                String adjStation = sc.nextLine().trim().toLowerCase();
                                if (adjStation.isEmpty()) break;

                                if (!MetroTicketBookingSystem.isValidStation(adjStation)) {
                                    System.out.println("Invalid adjacent station code.");
                                    continue;
                                }

                                System.out.print("Enter distance in km to " + adjStation + ": ");
                                int dist = safeInt();
                                if (dist <= 0) {
                                    System.out.println("Distance must be positive.");
                                    continue;
                                }

                                // Update adjacency in stationInfoMap both ways

                                StationInfo newStationInfo = MetroTicketBookingSystem.datastore.stationInfoMap.get(sName);
                                StationInfo adjStationInfo = MetroTicketBookingSystem.datastore.stationInfoMap.get(adjStation);

                                newStationInfo.addDistance(adjStation, dist);
                                adjStationInfo.addDistance(sName, dist);

                                System.out.println("Added adjacency: " + sName + " <-> " + adjStation + " with distance " + dist + " km.");
                            }
                        }


                    } else {
                        System.out.println("Failed to save station location.");
                    }


                    break;
                case 12:
                    System.out.print("Enter station name: ");
                    String stationName = sc.nextLine().trim().toLowerCase();
                    StationInfo info = datastore.stationInfoMap.get(stationName);
                    if (info == null) {
                        System.out.println("No such station.");
                    } else {
                        System.out.print("Enter new description: ");
                        String desc = sc.nextLine();
                        info.description = desc;

                        System.out.print("Has Restrooms? (yes/no): ");
                        String rest = sc.nextLine().toLowerCase();
                        while (!rest.equals("yes") && !rest.equals("no")) {
                            System.out.println("Invalid input. Please enter 'yes' or 'no'.");
                            System.out.print("Has Restrooms? (yes/no): ");
                            rest = sc.nextLine().toLowerCase();
                        }
                        info.hasRestrooms = rest.equals("yes");

                        System.out.print("Has Parking? (yes/no): ");
                        String park = sc.nextLine().toLowerCase();
                        while (!park.equals("yes") && !park.equals("no")) {
                            System.out.println("Invalid input. Please enter 'yes' or 'no'.");
                            System.out.print("Has Parking? (yes/no): ");
                            park = sc.nextLine().toLowerCase();
                        }
                        info.hasParking = park.equals("yes");

                        System.out.print("Has WiFi? (yes/no): ");
                        String wifi = sc.nextLine().toLowerCase();
                        while (!wifi.equals("yes") && !wifi.equals("no")) {
                            System.out.println("Invalid input. Please enter 'yes' or 'no'.");
                            System.out.print("Has WiFi? (yes/no): ");
                            wifi = sc.nextLine().toLowerCase();
                        }
                        info.hasWifi = wifi.equals("yes");


                        // Persist changes to DB
                        boolean success = DBManager.insertOrUpdateStationInfo(stationName, info.description,
                                info.hasRestrooms, info.hasParking, info.hasWifi);
                        if (success) {
                            System.out.println("Station info updated and saved to database.");
                        } else {
                            System.out.println("Failed to update station info in database.");
                        }
                    }
                    break;

                case 13:
                    DBManager.backupCurrentStations(MetroTicketBookingSystem.datastore.stations);
                    break;
                case 14:
                    if (DBManager.restoreStationsFromBackup()) {
                        // Reload in-memory stations list and stationInfoMap from DB
                        MetroTicketBookingSystem.datastore.stations = DBManager.getAllStationNames();
                        // Optional: clear and recreate stationInfoMap for each station as before
                        System.out.println("Stations list rolled back and in-memory list reloaded!");
                    }
                    break;
                case 15:
                    System.out.print("Are you sure you want to clear ALL data? This cannot be undone! (yes/no): ");
                    String confirm = MetroTicketBookingSystem.sc.nextLine().trim().toLowerCase();
                    if (confirm.equals("yes")) {
                        adminUser.clearDatabase();
                        for (User u : MetroTicketBookingSystem.datastore.users) {
                            u.monthlyPasses.clear();
                        }

                        // After clearing DB, also clear in-memory data:
                        MetroTicketBookingSystem.datastore.users.clear();
                        MetroTicketBookingSystem.datastore.allTickets.clear();
                        MetroTicketBookingSystem.datastore.feedbacks.clear();
                        MetroTicketBookingSystem.datastore.supportTickets.clear();
                        // MetroTicketBookingSystem.datastore.monthly_passes.clear();
                        MetroTicketBookingSystem.datastore.announcements.clear();
                        MetroTicketBookingSystem.datastore.stationInfoMap.clear();
                        MetroTicketBookingSystem.datastore.stations.clear();
                        MetroTicketBookingSystem.datastore.sortedUsers.clear();
                        MetroTicketBookingSystem.allPersons.clear();
                        System.out.println("✅ All data cleared from database and memory!");
                        // Optionally reload default admin and support staff here if needed

                        // ---------- Add default stations to DB ----------
                        List<String> defaultStations = Arrays.asList("a", "b", "c", "d", "e");
                        for (String s : defaultStations) {
                            DBManager.insertOrUpdateStationLocation(s, 0, 0);
                        }
                        // ---------- Reload in-memory station set from DB ----------
                        MetroDataStore datastore = MetroDataStore.getInstance();
                        MetroTicketBookingSystem.datastore.stations = DBManager.getAllStationNames();

                        // ---------- Properly initialize all station connections/distances ----------
                        setupStations(); // <---- THIS LINE ensures all graph edges and info is correct.

                        System.out.println("Default stations and info recreated. System is usable.");
                    } else {
                        System.out.println("Operation cancelled.");
                    }
                    break;


                case 16:
                    logAudit(adminUser.username, "Logged out");
                    System.out.println("Logging out...");
                    return;
                default:
                    System.out.println("Invalid choice.");
            }
        }
    }

    private static void supportMenu(SupportStaff staff) {
        while (true) {
            System.out.println("\n" + "=".repeat(48));
            System.out.printf("🧑‍💼  Support Staff Menu  —  %s%n", staff.username);
            System.out.println("=".repeat(48));
            System.out.println("🎫 1. View Assigned Tickets");
            System.out.println("✏️ 2. Update Support Ticket Status");
            System.out.println("↩️ 3. Logout");
            System.out.println("-".repeat(48));
            System.out.print("👉 Enter your choice (1-3): ");

            int ch = safeInt();
            switch (ch) {
                case 1:
                    staff.viewAssignedTickets();
                    break;
                case 2:
                    System.out.print("Enter Support Ticket ID: ");
                    int tid = safeInt();
                    System.out.print("Enter new status (OPEN, IN_PROGRESS, RESOLVED): ");
                    String sstatus = sc.nextLine().toUpperCase();

                    if (!sstatus.equals(SupportTicketStatus.OPEN)
                            && !sstatus.equals(SupportTicketStatus.IN_PROGRESS)
                            && !sstatus.equals(SupportTicketStatus.RESOLVED)) {
                        System.out.println("Invalid status.");
                    } else {
                        staff.updateTicketStatus(tid, sstatus);
                    }

                    break;
                case 3:
                    logAudit(staff.username, "Logged out");
                    System.out.println("Logging out...");
                    return;
                default:
                    System.out.println("Invalid option.");
            }
        }
    }
    static void displayAllStations() {
        System.out.println("Available Stations:");
        for (String code : MetroTicketBookingSystem.datastore.stations) {
            StationInfo info = MetroTicketBookingSystem.datastore.stationInfoMap.get(code);
            if (info != null) {
                System.out.printf(" %s : %s%n", code.toUpperCase(), info.description);
            }
        }
    }

    static void viewStationInfo() {
        displayAllStations();  // Show station codes and full names before prompt

        System.out.print("Enter station code to view info: ");
        String stCode = sc.nextLine().trim().toLowerCase();

        StationInfo info = MetroTicketBookingSystem.datastore.stationInfoMap.get(stCode);
        if (info == null) {
            System.out.println("Invalid station code.");
            return;
        }

        System.out.println("\n=== Station Information ===");
        System.out.printf(" Station Code: %s\n", stCode.toUpperCase());
        System.out.printf(" Name       : %s\n", info.description);
        System.out.printf(" Restrooms  : %s\n", info.hasRestrooms ? "Available" : "Not Available");
        System.out.printf(" Parking    : %s\n", info.hasParking ? "Available" : "Not Available");
        System.out.printf(" WiFi       : %s\n", info.hasWifi ? "Available" : "Not Available");

        System.out.println(" Adjacent Stations & Distances (km):");
        for (Map.Entry<String, Integer> entry : info.distances.entrySet()) {
            System.out.printf("  - %s : %d km%n", entry.getKey().toUpperCase(), entry.getValue());
        }

        DBManager.StationLocation loc = DBManager.getStationLocation(stCode);
        if (loc != null) {
            System.out.printf(" Coordinates: (%.2f, %.2f)%n", loc.x, loc.y);
        } else {
            System.out.println(" Coordinates: Not available");
        }
    }


    private static int safeInt() {
        try { return Integer.parseInt(sc.nextLine().trim()); }
        catch (Exception e) { return -1; }
    }

    private static double safeDouble() {
        try { return Double.parseDouble(sc.nextLine().trim()); }
        catch (Exception e) { return -1; }
    }
}
