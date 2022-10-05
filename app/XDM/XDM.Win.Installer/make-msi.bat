DEL /s /q *.wixobj
DEL /s /q net4.6.0.wxs
RMDIR /S /Q BIN

MKDIR BIN
MKDIR BIN\chrome-extension
MKDIR XDM.App.Host

dotnet build -c Release -f net4.6.0 ..\XDM.Wpf.UI\XDM.Wpf.UI.csproj -o bin
dotnet build -c Release -f net4.6.0 ..\XDM.App.Host\XDM.App.Host.csproj -o bin\XDM.App.Host

copy /B ffmpeg-x86.exe BIN
git clone https://github.com/subhra74/xdm-helper-chrome.git

xcopy /E xdm-helper-chrome\chrome\extension BIN\chrome-extension

heat dir BIN -o net4.6.0.wxs -scom -frag -srd -sreg -gg -cg NET460 -dr INSTALLFOLDER

candle product.wxs net4.6.0.wxs
light -ext WixUIExtension -ext WixUtilExtension -cultures:en-us product.wixobj net4.6.0.wixobj -b BIN -out xdmsetup.msi

