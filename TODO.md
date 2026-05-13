# TODO

- Update Test Mode
    - BUG: You can start calibration test mode without pressing LISTEN but it won't work unless you are listening.
        - Starting Test Mode should auto trigger the LISTEN button and stopping test mode should stop listening.
    - Update Calibration
        - Calibration shots should be created as Shot Event objects, including recording the audio of the shots.
        - When Test Mode ends, auto save all the calibration shots into a new Shot Series "Auto Calibration <date>" which is then added to the Shot Series table.
        The ShotSeries object for calibration should be separate from the active shot series if one is in progress. While in test mode, add shots to the calibration shot series instead of the active shot series. When calibration ends, resume with the active shot series.
        - Regardless of how Test Mode ends (i.e. clicking Stop Test, clicking Stop Listening, exiting the app, etc) do the calibration shot series save behavior and never prompt the user when saving the calibration data, just let it be a background process that the user doesn't need to know about other than seeing it in the shot event series.
        - Clear samples will clear out the shot events from the calibration shot series or just instantiate a new shot series
    - Update Calibration UI
        - Change Test Mode button to "Auto Calibrate"
        - Change "Test Mode Active" to "Auto Calibration in Progress"
            - Make bigger and centered
            - Indicate in an animated way that Auto Calibration is in progress, such as flashing the text different colors or the background of it or something, so long as there is some kind of motion or flashing to draw attention
            - Replace the text output with a shot event table like when you click on a shot series row
            - Above the table, put "Test Shots: X / 10" and under that and smaller as a side note "Collect at least 10 calibration shots to determine calibration settings."
                - Make sure the X / 10 is a thick bold font.
                - For the first number X, have the color of it change on a gradiant between red and green based on how many shots have been fired. So start with 0 as red and then progressively go through orange, yellow, green, etc as the calibration shot number increases towards the goal. You originally said 10 to 20 shots, so maybe 10 should be yellow and 20 is green. Or 10 is dark green and the more shots, the brighter the green gets.
            -
            