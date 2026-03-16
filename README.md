# GPS Sender

An Android app that reads RTK GPS data from a serial port and forwards location updates to a configurable HTTP API endpoint.

## Configuration

This project uses `local.properties` to keep sensitive values out of source control.

1. Copy `local.properties.example` to `local.properties` at the root of the project:
   ```bash
   # Linux / macOS
   cp local.properties.example local.properties

   # Windows (Command Prompt)
   copy local.properties.example local.properties
   ```
2. Fill in your values in `local.properties`:
   ```properties
   API_KEY=your_api_key_here
   API_URL=https://your-server.example.com/api/location
   DEVICE_ID=your_device_id_here
   ```
3. Build the project — the values are injected at compile time via `BuildConfig`.

> **Note**: `local.properties` is listed in `.gitignore` and must never be committed to version control.
