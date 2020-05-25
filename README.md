# ghidra_psx_ldr
Sony Playstation PSX executables loader for GHIDRA

Video tutorial on how to deal with overlays: https://youtu.be/DuQQfjTkkQc

# Building and installing
* Make sure [Gradle is installed](https://gradle.org/install/) in your system.
* Clone the ghidra_psx_ldr repository to a local directory in your computer.
* In your local ghidra_psx_ldr directory, run `env GHIDRA_INSTALL_DIR=/PATHTOYOURGHIDRAINSTALLATION gradle buildExtension`. This will build the project, generating a .zip file in the newly created dist directory for use with your Ghidra installation.
* Open Ghidra, go to File -> Install Extensions... and select the .zip file generated by the previous step.

# Screenshots

![Screen1](/imgs/screen1.png?raw=true)
![Screen7](/imgs/screen7.png?raw=true)
![Screen4](/imgs/screen4.png?raw=true)
![Screen5](/imgs/screen5.png?raw=true)
![Screen6](/imgs/screen6.png?raw=true)

