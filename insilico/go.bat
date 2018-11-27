 adb push adult#001Input562.txt /storage/emulated/0/Android/data/info.nightscout.androidaps/files/imports/input.txt
 rem adb shell am broadcast -a org.nightscout.androidaps.ACTION_READ_SF --es "input" "input.txt" --es "output" "output.txt" --es "clear" "true"
 adb shell am broadcast -a org.nightscout.androidaps.ACTION_READ_SF --es "input" "input.txt" --es "output" "output.txt"
