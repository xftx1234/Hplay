# 实机测试辅助（第 65 轮）
$adb = "D:\工作区\工具\android-build\platform-tools\adb.exe"
$act = $args[0]
if ($act -eq "dump") {
  & $adb shell uiautomator dump /data/local/tmp/ui.xml 2>&1 | Out-Null
  $xml = (& $adb shell cat /data/local/tmp/ui.xml 2>&1 | Out-String)
  Write-Output ("LEN=" + $xml.Length)
  $n = ([regex]::Matches($xml, "图载失败")).Count
  Write-Output ("THUMBFAIL=" + $n)
  Write-Output "TEXTS:"
  [regex]::Matches($xml, "text=.([^"`"]+)") | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -ne "" } | Select-Object -Unique | ForEach-Object { Write-Output ("  " + $_) }
}
elseif ($act -eq "tap") {
  & $adb shell input tap $args[1] $args[2] 2>&1 | Out-Null
  Write-Output ("TAPPED " + $args[1] + "," + $args[2])
}
elseif ($act -eq "shot") {
  & $adb shell screencap -p /sdcard/_s.png 2>&1 | Out-Null
  & $adb pull /sdcard/_s.png $args[1] 2>&1 | Out-Null
  Write-Output ("SHOT " + $args[1])
}
elseif ($act -eq "crash") {
  $logs = (& $adb logcat -d 2>&1 | Out-String)
  Write-Output ("FATAL=" + ([regex]::Matches($logs, "FATAL EXCEPTION")).Count)
  Write-Output ("ANR=" + ([regex]::Matches($logs, "ANR in com.han1me")).Count)
}
elseif ($act -eq "clearlog") { & $adb logcat -c 2>&1 | Out-Null; Write-Output "CLEARED" }
elseif ($act -eq "back") { & $adb shell input keyevent 4 2>&1 | Out-Null; Write-Output "BACK" }