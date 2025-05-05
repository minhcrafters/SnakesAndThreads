
# Snakes and Threads

**A Minecraft mod which allows executing Python 3 scripts with direct access to the API.**

The mod integrates GraalPy (GraalVM's Python runtime) into your Minecraft server, allowing server administrators and scripters to automate tasks, add custom game logic, and interact with the Minecraft world using Python.


## Table of Contents

*   [Features](#features)
*   [Requirements](#requirements)
*   [Installation](#installation)
*   [Usage](#usage)
    *   [Creating Scripts](#creating-scripts)
    *   [Running Scripts (Commands)](#running-scripts-commands)
*   [Python API (`mc` object)](#python-api-mc-object)
*   [Configuration](#configuration)
*   [Building from Source](#building-from-source)
*   [Troubleshooting](#troubleshooting)
*   [License](#license)
*   [Contributing](#contributing)


## Features

*   **Python 3 Scripting:** Execute standard Python 3 code directly on the server.
*   **Bundled GraalPy:** No need for server owners to install GraalVM; the Python runtime is included in the mod JAR.
*   **Minecraft API:** A `mc` object is injected into the Python script scope, providing functions to interact with the server (chat, commands, blocks, players, etc.).
*   **Direct Java Interop:** Leverage GraalVM's Polyglot capabilities to directly access and use Java classes (including Minecraft classes) from Python (requires careful permission configuration).
*   **In-Game Commands:**
    *   `/pyexec <filename>`: Executes a Python script file from the configuration directory. Supports Tab-completion for filenames.
    *   `/pyeval <code>`: Executes a raw string of Python code (requires high permission level).
*   **Asynchronous Execution:** Scripts run on a separate thread pool to minimize impact on server performance, although API calls interacting with Minecraft often block the script thread until the server thread completes the action.


## Requirements

*   **Minecraft:** Version 1.21.4
*   **Fabric Loader:** Version 0.15.11 or higher.
*   **Fabric API:** Version compatible with Minecraft 1.21.4 (e.g., 0.100.0+).
*   **Java Runtime Environment (JRE/JDK):** Version 21 or higher. **GraalVM installation is NOT required.**


## Installation

1.  **Download:** Obtain the PyFabric JAR file from the releases page or build it yourself.
2.  **Install Fabric:** Ensure you have a Fabric server set up for Minecraft 1.21.4.
3.  **Install Fabric API:** Download the correct Fabric API JAR for your Minecraft version and place it in the server's `mods` folder.
4.  **Install PyFabric:** Place the downloaded PyFabric JAR file into the server's `mods` folder.
5.  **Start Server:** Launch your Fabric server. On the first run, PyFabric should create its configuration directory.


## Usage

### Creating Scripts

1.  After starting the server once with the mod installed, a configuration directory will be created at: `[Your Server Root]/config/pyfabric/`
2.  Inside this directory, create another directory named `scripts` if it doesn't exist.
3.  Place your Python script files (with a `.py` extension) inside the `config/pyfabric/scripts/` directory.

**Example Script (`config/pyfabric/scripts/hello.py`):**

```python
# Standard Python modules are available
import time
import sys

# The 'mc' object is injected automatically - use its methods!
mc.log_info(f"--- Hello Script Starting (Python {sys.version_info.major}.{sys.version_info.minor}) ---")

# Send a message to the in-game chat
mc.send_chat("Hello Minecraft world from Python!")

# Get info about who ran the script
executor_name = mc.get_executor_name()
executor_pos = mc.get_executor_pos() # This blocks the script briefly!

mc.log_info(f"Script executed by: {executor_name}")

if executor_pos:
    mc.send_chat(f"Hello {executor_name} at {executor_pos['x']:.1f}, {executor_pos['y']:.1f}, {executor_pos['z']:.1f}!")
else:
    mc.send_chat(f"Hello {executor_name} (from console?)")

# Wait a bit
time.sleep(1.5)

mc.send_chat("Python script says goodbye!")
mc.log_info("--- Hello Script Finished ---")

# You can optionally return simple values
# return "Script completed successfully."
```

### Running Scripts (Commands)

Use the following commands in-game or in the server console:

*   `/pyexec <script_filename.py>`
    *   Executes the specified script file located in `config/pyfabric/scripts/`.
    *   **Tab Completion:** Pressing `Tab` after `/pyexec ` will suggest available `.py` files.
    *   **Default Permission:** OP Level 2 (configurable in `PyCommands.java`)
    *   **Example:** `/pyexec hello.py`

*   `/pyeval <python_code_string>`
    *   Executes the given string directly as Python code.
    *   **WARNING:** This command is extremely dangerous if used improperly, as it allows executing arbitrary code with server permissions. Use with extreme caution.
    *   **Default Permission:** OP Level 4 (configurable in `PyCommands.java`)
    *   **Example:** `/pyeval mc.send_chat('Eval test!')`

---

## Python API (`mc` object)

The `mc` object is automatically available in your Python script's global scope. It provides the following methods to interact with Minecraft:

*(Note: Methods interacting with game state typically run required actions on the main server thread. Calls returning data often **block** the Python script until the server thread task completes.)*

*   `mc.log_info(message: str)`: Logs a message to the server console at INFO level.
*   `mc.log_warning(message: str)`: Logs a message to the server console at WARN level.
*   `mc.log_error(message: str)`: Logs a message to the server console at ERROR level.
*   `mc.send_chat(message: str)`: Broadcasts a message to all players in the chat. (Runs on server thread).
*   `mc.run_command(command: str)`: Executes a server command as if run by the script executor (player or console). **Use with extreme caution!** (Runs on server thread).
*   `mc.get_player_pos(player_name: str) -> dict | None`: Returns the position `{'x': float, 'y': float, 'z': float}` of the specified online player, or `None` if not found. (Blocks script).
*   `mc.get_player_dimension(player_name: str) -> str | None`: Returns the dimension ID string (e.g., `"minecraft:overworld"`) for the specified online player, or `None` if not found. (Blocks script).
*   `mc.teleport_player(player_name: str, x: float, y: float, z: float, dimension_id: str) -> bool`: Teleports the specified player. Returns `True` on success (teleport initiated), `False` on failure (player/dimension not found, error). (Blocks script).
*   `mc.get_block(x: int, y: int, z: int, dimension_id: str) -> str | None`: Returns the block ID string (e.g., `"minecraft:stone"`) at the given coordinates, or `None` if the chunk is unloaded or an error occurs. (Blocks script).
*   `mc.set_block(x: int, y: int, z: int, block_id: str, dimension_id: str) -> bool`: Sets the block at the given coordinates. Returns `True` if the block was successfully set (according to the server), `False` otherwise (e.g., chunk unloaded, invalid ID, cancelled by protection). (Blocks script).
*   `mc.get_executor_name() -> str`: Returns the name of the command source that executed the script (e.g., player name, "Server").
*   `mc.get_executor_pos() -> dict | None`: Returns the position `{'x': float, 'y': float, 'z': float}` of the command source if it's an entity (like a player), otherwise `None`. (Blocks script).
*   `mc.get_executor_dimension() -> str | None`: Returns the dimension ID string of the command source if it's an entity in a world, otherwise `None`. (Blocks script).

```python
import java

try:
    # Get a Java class
    JavaString = java.type('java.lang.String')
    # Create an instance
    java_str = JavaString("Hello from Java via Python!")
    # Call Java methods
    length = java_str.length()
    mc.log_info(f"Created Java String: '{java_str}', Length: {length}")

    # Example: Accessing Minecraft classes
    # BlockPos = java.type('net.minecraft.util.math.BlockPos')
    # pos_obj = BlockPos(0, 64, 0)
    # mc.log_info(f"Created BlockPos object: {pos_obj.toShortString()}")

except Exception as e:
    mc.log_warning(f"Java interop failed: {e}")
```

---

## Configuration

*   **Script Directory:** `config/pyfabric/scripts/` - Place your `.py` scripts here.

---

**NEVER** run Python scripts from untrusted sources. A malicious script could damage your world, ban players, steal data, or compromise the server.

**Treat Python scripts with the same caution as you would server plugins or mods.**

---

## Building from Source

1.  **Prerequisites:**
    *   JDK (Java Development Kit) version 21 or higher.
    *   Git (optional, for cloning).
2.  **Clone:** `git clone https://github.com/minhcrafters/SnakesAndThreads.git`
3.  **Build:** Navigate into the project directory and run the standard Gradle wrapper command:
    *   Linux/macOS: `./gradlew build`
    *   Windows: `gradlew build`
4.  **Output:** The built JAR file (including bundled dependencies) will be located in `build/libs/`.

---

## Troubleshooting

*   **`An unexpected error occurred trying to execute that command` (In-Game):** This usually means an exception occurred *before* the script execution started (e.g., Python Executor failed to initialize, file not found, permission error). **Check the server log (`logs/latest.log`) immediately** for the full error stack trace.
*   **`Python Executor is not available...`:** The GraalVM context failed to initialize during server startup. Check the *very beginning* of the server log for `FATAL: Failed to initialize bundled GraalVM Python context!` and related errors. This could be due to incompatible GraalVM artifacts, missing dependencies, or classpath issues.
*   **`PolyglotException: ImportError: No module named '...'`:** GraalPy cannot find a Python module.
    *   If it's a *standard library* module (like `os`, `sys`, `json`), it likely means GraalPy's bundled stdlib wasn't found/loaded correctly. Check `build.gradle` dependencies (`python-community`) and GraalVM options (`python.ForceImportSite`). Ensure the JAR wasn't corrupted.
    *   If it's a *third-party* module, it needs to be pure Python and placed correctly so Python's import system can find it (e.g., inside the `scripts` directory or a subdirectory, and imported relatively). Libraries with C extensions likely won't work.
*   **`PolyglotException: TypeError: ...` / `AttributeError: ...` / etc.:** Standard Python runtime errors within the script code. Debug the Python script logic. The error message and traceback (logged by the executor) should help pinpoint the issue.
*   **`PolyglotException: IllegalStateException: MinecraftServer instance not available...` (or similar HostException):** An API call requiring the server instance was made too early (before `ServerLifecycleEvents.SERVER_STARTED`) or too late (during server shutdown). Ensure scripts run when the server is fully started.
*   **`PolyglotException: java.lang.SecurityException: Access denied for Java type...`:** Python code tried to access a Java class using `polyglot.import_value` but `allowHostClassLookup` denied it. Adjust the predicate in `PythonExecutor.java`.
*   **`PolyglotException: TypeError: Access to host field/method denied...`:** Python code tried to access a Java field or method, but `allowHostAccess` denied it. Ensure the method/field is public and either use `HostAccess.ALL` (unsafe) or `HostAccess.EXPLICIT` with `@HostAccess.Export` annotations on allowed Java methods.

---

## License

This project is licensed under the [MIT License](LICENSE.txt) (or specify your chosen license). Note that GraalPy Community Edition itself is typically under GPLv2 with Classpath Exception - ensure compliance.

---

## Contributing

Contributions are welcome! Please feel free to submit issues or pull requests to the [repository link - TODO].

---