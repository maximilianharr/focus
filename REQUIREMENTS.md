# REQUIREMENTS

Your task is to implement an app that blocks other apps and website on an android phone. The details of the app are described below.

## DESIGN

- The app has three tabs in a bar listed at the bottom
- The three tabs are: "Block List", "Schedule" and "Settings"

Block List
- Contains a list of blocked app, a small horizontal separator and a list of blocked website
- At bottom right is a "+" sign to add a blocked item
- When clicking on the "+" sign a popup opens
- The popup title is "Add a block" and has two tabs "App" and "Website"
- When clicking "App" then the user can search the apps, limited to launchable user apps (apps with a home-screen/launcher icon), not all installed packages or system components
- The user can select multiple apps to add from that list
- When adding an app in the "Add a block" popup show the app icons on the left of the row and then the name. Mark a selected app by make the background darker.
- When clicking the "Website" tab a text field can be set with the website, e.g. like tiktok.com; input is normalized (strip scheme/www, lowercase) before storing
- At the bottom of the tab is a "Daily allowance" slider that the user can set from 0 (default) to 120 minutes in 5 minute steps, which marks the time an app/website is allowed to run
  - 0 = fully blocked all day (no usage at all) unless a Schedule "cheat window" is currently active
  - Above 0 = that many cumulative minutes per day allowed before it locks for the rest of the day
  - The allowance resets at local midnight
- If a timer is set show the timer as "14/15 min" where the first number is the amount left and the second number is the timer
- Below the app slider is a "cancel" and a "Add" button
- An app or website block can always be added, even if "edit mode" (see settings) is off
- Removing an existing blocked app/website requires edit mode (unlike adding, which is always allowed)
- To remove an app add a "trash can" icon on the right of the app block to indicate "clickability" make it dark if edit mode is on and bright if off.
- In the block List tab when clicking on an existing app/website block a popup shall open where the user can adapt the daily allowance

Schedule
- At the bottom of the schedule tab is a "+" sign to add a time window
- Multiple time windos can be added
- By default no time window is present
- When clickling on the "+" sign a time window is added directly as an item
- A time window has a start and end time. When clicking on a time a clock popup opens where the user can set the respective time
- An time window has the weekday first letters "MTWTFSS" on top left on which the time window is active
- these letters can be clicked to active the schedule, the respective letter then gets a darker color to indicate activation
- By default when adding a timer window in Schedule tab activate the current day to make it visible to the user that these have different colors and can be activated
- On top right of the item is a delete button to remove a time window
- A time window is a time window to cheat not to focus, during that time the user can use the apps/websites
- A time window can only be added if in Edit mode (see "edit mode" in settings). To indicate "clickability" make it dark if edit mode is on and bright if off.
- Toggling weekday letters, changing start/end time, and deleting an existing time window also require edit mode - only viewing is free (since an active window disables blocking, it must go through the same cooldown ritual as other loosening changes)
- Overnight windows (crossing midnight) and multiple overlapping windows are supported; any currently-active window disables blocking

Settings
- Settings tab has multiple buttons in the following order
- Edit mode with button "Turn On"/"Turn Off" enteres edit mode.
- in edit mode all settings can be changed
- To enter edit mode the user has to wait for "cooldown" countdown
- The cooldown countdown is diplayed on the edit mode button if the user clicks it (state "Turn On")
- After the cooldown countdown a confirm countdown is displayed during which the button oscillated from dark/bright color at 1 Hz
- If the user missed the cooldown timer then the edit mode will not be turned on. The botton goes back to "Turn On" state.
- There is also an "edit mode" timer. The "edit mode" timer is a time window to keep edit mode alive if the app is not used. It will be revoked after this time. The "edit mode time" is not used while the user uses the app and is reset if the user re-opens the app before the timer is zero.
- The cooldown/confirm/edit mode countdowns keep running even if the app is backgrounded or the screen is locked; missing the confirm window still lapses back to "Turn On"
- Below the edit mode is a "Set timers" which opens a popup in which the user can set the cooldown, confirm and edit mode timers in minutes.
- One decimal point is allowed for the timers (e.g. 0.1 for 6 seconds)
- The timer popup has a "cancel" and "save" button
- If the cooldown timer is above 30 minutes or the confirm window is below 1 minute the user is asked if he is sure to set these values
- By default cooldown timer is 0.1 minutes and confirm window is 1 minute to give the user the option to test the app
- The timers can only be set in edit mode
- Below the timer button is a "no color" toggle that forces color correction. It can always be turned on, but only turned off during active edit mode.
- Below this is a permissions button that opens a status screen showing whether Accessibility Service, VPN, Device Admin and notification permissions are granted, each with a button to jump to the relevant Android settings screen to fix it. This is also where the app routes the user if it detects a required permission was revoked.
- Below this is Backup section with two buttons "Export" and "Import" in one row that allow the user to save/load his settings into a .json file (via the standard Android file picker). Export is always possibe. Import only when in edit mode, and overwrites all current settings.
- Below this is a shutdown button to shutdown the app. Only possible to click in edit mode. Shutdown fully stops the blocking (Accessibility Service, VPN, watchdog) and closes the app; blocking stays off until the phone reboots or the app is reopened manually. When restarting the phone the app is launched again.

Top bar
- At the top are two bars in two rows "Focus: ON/OFF" and "Edit: ON/OFF" to indicate whether focus or edit mode are activated
- An activated focus/edit mode is indicate by a darker color
- "Edit: ON/OFF" reflects edit mode. "Focus: ON/OFF" reflects real-time enforcement status: ON while actively enforcing, OFF while shut down or while a Schedule cheat window is currently active
- The topmost bar shall be right below the phones status bar to make sure the user can still read battery life, networks, etc.
- The topmost bar uses the apps dark color as background color.

Design
- Keep the app in two colors, vary dark and very bright modern grayish color
- Give the app a nice, modern looking design while keeping it simple, tidy, lean and clean.

## BEHAVIOUR
- The app shall not throw interruptive notifications (popups/heads-up alerts), because they distract the user. A silent, permanent, low-priority status icon (foreground service) and the OS-mandated VPN icon are unavoidable and accepted.
- The app cannot be uninstalled by the user on the phone
- The app can be uninstalled in USB debugging mode via a connected laptop
- The app blocks cannot be cheated around, e.g. by removing permissions via android settings
- Settings can only be changed in "edit mode", see above

### Enforcement architecture
- Target phone is the user's daily-driver with accounts already set up, so Device Owner mode (which would make the above guarantees close to absolute) is not available - it requires a factory-reset device with zero accounts
- Fallback approach, "best effort" like Freedom/AppBlock/Opal rather than a hard technical lock:
  - AccessibilityService detects the foreground app, blocks it, and tracks its usage time for the daily allowance
  - A local VpnService blocks websites at the DNS level, system-wide, including subdomains, and also blocks known DNS-over-HTTPS provider endpoints (Cloudflare, Google, etc.) so switching a browser to "Secure DNS" can't bypass it
  - Basic (non-owner) Device Admin adds friction to uninstalling
  - A determined user can still defeat this via Android Settings (disable Accessibility Service/VPN/Device Admin) - accepted as a known limitation of not using Device Owner mode
- When a required permission (Accessibility/VPN/Device Admin) is detected as revoked, the app shows an aggressive full-screen "protection disabled, re-enable now" lock screen that intercepts phone use until it's fixed, via a background watchdog service
- Blocking a blocked app/website (no OS "suspended" dialog is available without Device Owner) shows a custom full-screen "Blocked" overlay in the app's two-tone style: item name, reason (schedule/allowance exhausted), "Go Home" button

### Tech stack
- Kotlin + Jetpack Compose (Material 3), native Android app
- minSdk 26 (Android 8.0), targetSdk latest stable
- Local-only storage: Room DB (blocklists, schedule, usage tracking) + DataStore (settings, timers, edit-mode state); no backend, no cloud sync
