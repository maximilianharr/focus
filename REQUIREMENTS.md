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
- When clicking "App" then the user can search the standard apps, e.g. those he installed
- The user can select multiple apps to add from that list
- When clicking the "Website" tab a text field can be set with the website, e.g. like tiktok.com
- At the bottom of the tab is a "Daily allowance" slider that the user can set from 0 (default) to 60 minutes, which marks the time an app/website is allowed to run
- Below the app slider is a "cancel" and a "Add" button
- An app or website block can always be added, even if "edit mode" (see settings) is off

Schedule
- At the bottom of the schedule tab is a "+" sign to add a time window
- Multiple time windos can be added
- By default no time window is present
- When clickling on the "+" sign a time window is added directly as an item
- A time window has a start and end time. When clicking on a time a clock popup opens where the user can set the respective time
- An time window has the weekday first letters "MTWTFSS" on top left on which the time window is active
- these letters can be clicked to active the schedule, the respective letter then gets a darker color to indicate activation
- On top right of the item is a delete button to remove a time window
- A time window is a time window to cheat not to focus, during that time the user can use the apps/websites
- A time window can only be added if in Edit mode (see "edit mode" in settings)

Settings
- Settings tab has multiple buttons in the following order
- Edit mode with button "Turn On"/"Turn Off" enteres edit mode.
- in edit mode all settings can be changed
- To enter edit mode the user has to wait for "cooldown" countdown
- The cooldown countdown is diplayed on the edit mode button if the user clicks it (state "Turn On")
- After the cooldown countdown a confirm countdown is displayed during which the button oscillated from dark/bright color at 1 Hz
- If the user missed the cooldown timer then the edit mode will not be turned on. The botton goes back to "Turn On" state.
- Below the edit mode is a "Set timers" which opens a popup in which the user can set the cooldown and confirm timers in minutes.
- One decimal point is allowed for the timers (e.g. 0.1 for 6 seconds)
- The timer popup has a "cancel" and "save" button
- If the cooldown timer is above 30 minutes or the confirm window is below 1 minute the user is asked if he is sure to set these values
- By default cooldown timer is 0.1 minutes and confirm window is 1 minute to give the user the option to test the app
- The timers can only be set in edit mode
- Below the timer button is a permissions button to set the app/website blocking permissions. This is optional if needed (up to your implementation)
- Below this is Backup section with two buttons "Export" and "Import" in one row that allow the user to save/load his settings into a .json file. Export is always possibe. Import only when in edit mode.
- Below this is a shutdown button to shutdown the app. Only possible to click in edit mode. When restarting the phone the app is launched again.

Top bar
- At the top are two bars in two rows "Focus: ON/OFF" and "Edit: ON/OFF" to indicate whether focus or edit mode are activated
- An activated focus/edit mode is indicate by a darker color

Design
- Keep the app in two colors, vary dark and very bright modern grayish color
- Give the app a nice, modern looking design while keeping it simple, tidy, lean and clean.


## BEHAVIOUR
- The app shall not throw any notifactions, because they distract the user
- The app cannot be uninstalled by the user on the phone
- The app can be uninstalled in USB debugging mode via a connected laptop
- The app blocks cannot be cheated around, e.g. by removing permissions via android settings
- Settings can only be changed in "edit mode", see above
