# KzDesk diagnostics and log location

The diagnostic logs are initialized **before** `GlobalApp` starts the FreeRDP JNI layer, so startup failures are recorded too.

On Android, KzDesk writes both logs to:

`Internal storage/Android/media/com.kazamatech.kzdesk/log/`

- `kzdesk-boot.log` — app startup, connection lifecycle, and JNI callback diagnostics.
- `kzdesk-crash.log` — uncaught JVM crash stacks and recorded fatal setup errors.

A private duplicate is mirrored in the app's internal files directory if external media storage is unavailable. The app's **Diagnostik & Keamanan** screen displays the paths, shows the latest diagnostic text, and lets the user share it.

An installed APK is read-only; runtime logs cannot be written back inside the APK file itself. The in-app viewer/share action and the app-specific `Android/media` directory are the durable, retrievable options.

Logs may contain host names and usernames. Review them before sharing; passwords are not intentionally logged.
