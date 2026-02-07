set BUILD_VER=8.0.25

DEL /s /q *.wixobj
DEL /s /q net4.5.0.wxs
DEL /s /q net4.6.0.wxs
RMDIR /S /Q BIN\NET450
RMDIR /S /Q BIN\NET460
RMDIR /S /Q WIXOBJ\NET450
RMDIR /S /Q WIXOBJ\NET460
RMDIR /S /Q BIN\NET450\chrome-extension
RMDIR /S /Q BIN\NET460\chrome-extension

DEL /s /q *.wixpdb

MKDIR BIN
MKDIR BIN\NET450
MKDIR BIN\NET460
MKDIR BIN\NET450\chrome-extension
MKDIR BIN\NET460\chrome-extension
MKDIR WIXOBJ\NET450
MKDIR WIXOBJ\NET460

dotnet build -c Release -f net4.5 ..\XDM.Wpf.UI\XDM.Wpf.UI.csproj -o BIN\NET450
dotnet build -c Release -f net4.6 ..\XDM.Wpf.UI\XDM.Wpf.UI.csproj -o BIN\NET460
dotnet build -c Release -f net4.5 ..\XDM.WinForms.IntegrationUI\XDM.WinForms.IntegrationUI.csproj -o BIN\NET450
dotnet build -c Release -f net4.6 ..\XDM.WinForms.IntegrationUI\XDM.WinForms.IntegrationUI.csproj -o BIN\NET460

#copy /B binary-deps\ffmpeg-x86.exe BIN\NET450
#copy /B binary-deps\yt-dlp_x86.exe BIN\NET460


#xcopy /E ..\chrome-extension BIN\NET450\chrome-extension
#xcopy /E ..\chrome-extension BIN\NET460\chrome-extension

heat dir BIN\NET450 -o net4.5.0.wxs -scom -frag -srd -sreg -gg -cg NET4 -dr INSTALLFOLDER
heat dir BIN\NET460 -o net4.6.0.wxs -scom -frag -srd -sreg -gg -cg NET4 -dr INSTALLFOLDER

candle product-net4.5.0.wxs net4.5.0.wxs -o WIXOBJ\NET450\
candle product-net4.6.0.wxs net4.6.0.wxs -o WIXOBJ\NET460\

light -ext WixUIExtension -ext WixUtilExtension -cultures:en-us WIXOBJ\NET450\product-net4.5.0.wixobj WIXOBJ\NET450\net4.5.0.wixobj -b BIN\NET450 -out xdmsetup-%BUILD_VER%-win7.msi
light -ext WixUIExtension -ext WixUtilExtension -cultures:en-us WIXOBJ\NET460\product-net4.6.0.wixobj WIXOBJ\NET460\net4.6.0.wixobj -b BIN\NET460 -out xdmsetup-%BUILD_VER%.msi


