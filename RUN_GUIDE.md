# Multi-Threaded TCP Server - Run Guide

## Prerequisites

### 1. Copy Project Files
- Copy the project folder to both Windows and Mac laptops
- The same project code must be present on both machines

### 2. Verify Maven Installation

**On Mac:**
```bash
mvn --version
```

**On Windows:**
```cmd
mvn --version
```

If Maven is not installed:
- Mac: `brew install maven` or download from [Maven official site](https://maven.apache.org/download.cgi)
- Windows: Download from [Maven official site](https://maven.apache.org/download.cgi) and configure environment variables

---

## Scenario 1: Run Server on Mac, Run Client on Windows

### Step 1: Check IP Address on Mac
```bash
# In Mac terminal
ifconfig | grep "inet " | grep -v 127.0.0.1
```
or
```bash
ipconfig getifaddr en0
```

Example result: `192.168.1.100`

### Step 2: Run Server on Mac
```bash
# Navigate to project directory
cd /Users/kimbyeolha/Desktop/Multi-Threaded-TCP-Server-Using-Maven-add-file-transfer

# Compile and run server (using port 12345)
mvn clean compile
mvn exec:java -Dexec.mainClass=edu.ServerMaven -Dexec.args="12345"
```

When the server is running:
```
TCP Server running on 12345
Press Ctrl+C to stop the server.
Client connected: ...
```

### Step 3: Check IP Address on Windows
```cmd
ipconfig
```
Look for "IPv4 Address". Example: `192.168.1.101`

### Step 4: Run Client on Windows
```cmd
# Navigate to project directory
cd C:\path\to\Multi-Threaded-TCP-Server-Using-Maven-add-file-transfer

# Compile client
mvn clean compile

# Run client (using Mac's IP address)
mvn exec:java -Dexec.mainClass=edu.TCPClient -Dexec.args="192.168.1.100 12345"
```

---

## Scenario 2: Run Server on Windows, Run Client on Mac

### Step 1: Check IP Address on Windows
```cmd
ipconfig
```
Look for "IPv4 Address". Example: `192.168.1.101`

### Step 2: Run Server on Windows
```cmd
# Navigate to project directory
cd C:\path\to\Multi-Threaded-TCP-Server-Using-Maven-add-file-transfer

# Compile and run server
mvn clean compile
mvn exec:java -Dexec.mainClass=edu.ServerMaven -Dexec.args="12345"
```

### Step 3: Check IP Address on Mac
```bash
ifconfig | grep "inet " | grep -v 127.0.0.1
```

### Step 4: Run Client on Mac
```bash
# Navigate to project directory
cd /Users/kimbyeolha/Desktop/Multi-Threaded-TCP-Server-Using-Maven-add-file-transfer

# Compile client
mvn clean compile

# Run client (using Windows IP address)
mvn exec:java -Dexec.mainClass=edu.TCPClient -Dexec.args="192.168.1.101 12345"
```
---

## Command Usage Guide

All commands available after connecting to the server and logging in.

### Quick Reference
- `send #<channel> <message>` - Send message to channel
- `send @<username> <message>` - Send private message
- `send #<channel> <file_path>` - Send file to channel
- `send @<username> <file_path>` - Send file to user
- `createTask description <description> [status <status>] [deadline <deadline>] [assignee <username>]` - Create a task
- `viewTasks` or `viewTasks [username]` - View tasks
- `updateTask <task_id> description <description> [status <status>] [deadline <deadline>] [assignee <username>]` - Update task
- `deleteTask <task_id>` - Delete task
- `viewDirectMessages` - View direct messages
- `viewChannelMessages <channel>` - View channel messages

### 1. Authentication

#### Register
```
register <username> <password>
```
Example:
```
register alice password123
```

#### Login
```
login <username> <password>
```
Example:
```
login alice password123
```

---

### 2. Messaging

#### Send Message to Channel
```
send #<channel_name> <message>
```
Example:
```
send #general Hello everyone!
send #dev-team Meeting at 3pm
```

#### Send Private Message to User
```
send @<username> <message>
```
Example:
```
send @alice How are you?
send @bob Can you review my code?
```

#### Send File (Channel or Private)
```
send #<channel_name> <file_path>
send @<username> <file_path>
```
Example:
```
send #general ./document.pdf
send @alice ./report.txt
```
- Files are automatically saved to the `downloads/` folder.

---

### 3. View Messages

#### View Direct Messages
```
viewDirectMessages
```
- Displays all private messages you sent/received in chronological order.

#### View Channel Messages
```
viewChannelMessages <channel_name>
```
Example:
```
viewChannelMessages general
viewChannelMessages #dev-team
```
- The `#` symbol is optional.
- Displays all messages in a specific channel in chronological order.

---

### 4. Task Management

#### Create Task
```
createTask description <task_description> [status <status>] [deadline <deadline>] [assignee <username>]
```
Example:
```
createTask description Fix login bug
createTask description Fix login bug status in_progress
createTask description Fix login bug deadline 2026-01-08
createTask description Fix login bug status in_progress deadline 2026-01-08 assignee Alice
```
**Parameters:**
- `description <task_description>`: Task description (required)
- `status <status>`: Status (optional, default: `pending`)
  - Possible values: `pending`, `in_progress`
  - Note: `completed` status cannot be set during creation. Use `updateTask` to mark as completed.
- `deadline <deadline>`: Deadline (optional)
  - Format: `YYYY-MM-DD` (e.g., `2026-01-08`)
- `assignee <username>`: Assignee username (optional)
  - The username must exist in the system

**Note:**
- Field names must be explicitly specified (same format as `updateTask`)
- You can specify any combination of optional parameters in any order

#### View All Tasks
```
viewTasks
```
- Displays all tasks in ID order.
- Information: Task ID, description, creator, assignee, status, deadline

#### View Tasks Assigned to Specific User
```
viewTasks <username>
```
Example:
```
viewTasks alice
viewTasks bob
```
- Filters and displays only tasks assigned to a specific user.

#### Update Task
```
updateTask <task_id> description <description> [status <status>] [deadline <deadline>] [assignee <username>]
```
**Fields:**
- `description <description>`: Change task description (required)
- `status <status>`: Change status (optional, one of: pending, in_progress, completed)
- `deadline <deadline>`: Set deadline (optional)
- `assignee <username>`: Change assignee (optional)

Example:
```
updateTask 1 description Fix critical security bug
updateTask 1 description Fix bug status in_progress
updateTask 1 description Fix bug deadline 2024-12-31
updateTask 1 description Fix bug status in_progress deadline 2024-12-31 assignee bob
updateTask 1 status completed  (only assigned user can mark as completed)
```

**Permissions:**
- Only the task creator can update description, deadline, and assignee.
- Only the task creator can change status to `pending` or `in_progress`.
- Only the assigned user can change status to `completed`.

#### Delete Task
```
deleteTask <task_id>
```
Example:
```
deleteTask 1
deleteTask 5
```
**Note:**
- Only the task creator can delete tasks.

---

## Usage Example Scenarios

### Scenario 1: Project Task Management

1. **Create Tasks:**
   ```
   createTask description Implement login feature
   createTask description Write API documentation status in_progress deadline 2026-01-10 assignee Alice
   createTask description Fix database connection issue status pending deadline 2026-01-15
   ```

2. **View Tasks:**
   ```
   viewTasks
   ```

3. **Update Task Status:**
   ```
   updateTask 1 status in_progress
   updateTask 1 deadline 2026-01-25
   ```

4. **View Assigned Tasks:**
   ```
   viewTasks alice
   ```

5. **Complete Task:**
   ```
   updateTask 2 status completed
   ```

### Scenario 2: Team Communication

1. **Send Message to Channel:**
   ```
   send #general Good morning team!
   send #dev-team Code review needed for PR #42
   ```

2. **Send Private Message:**
   ```
   send @alice Can you help me with the login feature?
   send @bob Meeting at 2pm today
   ```

3. **Share Document Files:**
   ```
   send #general ./project-plan.pdf
   send @alice ./requirements.txt
   ```

4. **View Message History:**
   ```
   viewChannelMessages general
   viewDirectMessages
   ```

---

## Status Codes

Task status can be one of the following:
- `pending`: Pending (default)
- `in_progress`: In progress
- `completed`: Completed

---

## Error Messages

When you enter a command incorrectly or don't have permission, the following error messages will be displayed:

- `ERROR: You must be logged in.` - Login is required.
- `ERROR: Task with ID X not found.` - Task ID does not exist.
- `ERROR: You can only update tasks that you created.` - Only the task creator can update.
- `ERROR: Only the assigned user can mark a task as completed.` - Only the assigned user can complete tasks.
- `ERROR: User 'username' not found.` - User does not exist.
- `ERROR: Invalid status. Must be: pending, in_progress, or completed` - Invalid status value.
- `ERROR: Task is not assigned to anyone. Assign the task first.` - Task must be assigned before marking as completed.