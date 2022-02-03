Rem heat dir net3.5 -o net3.5.wxs -scom -frag -srd -sreg -ag -cg NET35 -dr INSTALLFOLDER

robocopy ..\XDM.Wpf.UI\bin\x86\Release\net4.7.2 net4.7.2

heat dir net4.7.2 -o net4.7.2.wxs -scom -frag -srd -sreg -gg -cg NET472 -dr INSTALLFOLDER

candle product.wxs net4.7.2.wxs
light -ext WixUIExtension -ext WixUtilExtension -cultures:en-us product.wixobj net4.7.2.wixobj -b net4.7.2 -out xdmsetup.msi

