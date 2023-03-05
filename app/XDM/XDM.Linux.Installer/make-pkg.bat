set BUILD_VER=8.0.18

RMDIR /S /Q BIN

MKDIR BIN
MKDIR BIN\chrome-extension

dotnet publish -c Release -f net6.0 -r linux-x64 ..\XDM.Gtk.UI\XDM.Gtk.UI.csproj -o BIN
