set BUILD_VER=8.0.10

RMDIR /S /Q BIN
RMDIR /S /Q xdm-helper-chrome

MKDIR BIN
MKDIR BIN\chrome-extension
MKDIR BIN\ext-loader
MKDIR BIN\XDM.App.Host

dotnet publish -c Release -f net6.0 -r linux-x64 ..\XDM.Gtk.UI\XDM.Gtk.UI.csproj -o BIN
dotnet publish -c Release -f net6.0 -r linux-x64 ..\XDM.App.Host\XDM.App.Host.csproj -o BIN\XDM.App.Host

git clone https://github.com/subhra74/xdm-helper-chrome.git

xcopy /E xdm-helper-chrome\chrome\chrome-extension BIN\chrome-extension
xcopy /E xdm-helper-chrome\ext-loader BIN\ext-loader
