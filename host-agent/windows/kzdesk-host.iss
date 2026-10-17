; KzDesk Host - installer Windows (Inno Setup 6.3+). Dibangun oleh workflow rilis.
; Variabel lingkungan: KZ_VERSION (mis. 1.0.0), KZ_API_BASE (opsional).
#define AppName "KzDesk Host"
#define AppVersion GetEnv("KZ_VERSION")
#if AppVersion == ""
  #define AppVersion "0.0.0"
#endif
#define ApiBase GetEnv("KZ_API_BASE")
#if ApiBase == ""
  #define ApiBase "https://kzdesk.kazamasite.biz.id"
#endif

[Setup]
AppId={{B7D6A2E4-5C1F-4E8A-9A57-3C2D1E0F6A11}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=KazamaDev
AppPublisherURL=https://www.kazamasite.biz.id
AppSupportURL=https://kzdesk.kazamasite.biz.id
VersionInfoVersion={#AppVersion}
DefaultDirName={autopf}\KzDesk Host
DefaultGroupName=KzDesk Host
DisableProgramGroupPage=yes
OutputDir=..\..\dist
OutputBaseFilename=KzDesk-Host-Setup
SetupIconFile=kzdesk.ico
UninstallDisplayIcon={app}\kzdesk.ico
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
UsePreviousTasks=yes

[Tasks]
Name: "allowpublic"; Description: "Allow connections from the internet (Public network profile). A VPN such as Tailscale is safer."; Flags: unchecked

[Files]
Source: "*.ps1"; DestDir: "{app}"; Flags: ignoreversion
Source: "ShowCredentials.cmd"; DestDir: "{app}"; Flags: ignoreversion
Source: "qr.js"; DestDir: "{app}"; Flags: ignoreversion
Source: "kzdesk.ico"; DestDir: "{app}"; Flags: ignoreversion
Source: "VERSION"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\KzDesk Host - Device ID and Password"; Filename: "{app}\ShowCredentials.cmd"; IconFilename: "{app}\kzdesk.ico"

[Run]
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\KzDeskSetup.ps1"" -ApiBase ""{#ApiBase}""{code:AllowPublicArg}"; Flags: runhidden waituntilterminated; StatusMsg: "Configuring KzDesk Host..."

[UninstallRun]
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\KzDeskUninstall.ps1"""; Flags: runhidden waituntilterminated; RunOnceId: "KzDeskCleanup"

[Code]
function AllowPublicArg(Param: String): String;
begin
  if WizardIsTaskSelected('allowpublic') then Result := ' -AllowPublic' else Result := '';
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Code: Integer;
begin
  { Hentikan service lama agar berkasnya bisa ditimpa (saat update/pasang ulang). }
  Exec(ExpandConstant('{sys}\schtasks.exe'), '/End /TN "KzDesk Host Service"', '', SW_HIDE, ewWaitUntilTerminated, Code);
  Result := '';
end;

function JsonValue(const Json, Key: String): String;
var
  P, Q: Integer;
begin
  { Toleran terhadap spasi: PowerShell 5.1 menulis "key":  "value". }
  Result := '';
  P := Pos('"' + Key + '"', Json);
  if P = 0 then Exit;
  P := P + Length(Key) + 2;
  while (P <= Length(Json)) and (Json[P] <> '"') do P := P + 1;
  P := P + 1;
  Q := P;
  while (Q <= Length(Json)) and (Json[Q] <> '"') do Q := Q + 1;
  Result := Copy(Json, P, Q - P);
end;

procedure CurPageChanged(CurPageID: Integer);
var
  Raw: AnsiString;
begin
  if CurPageID = wpFinished then
    if LoadStringFromFile(ExpandConstant('{commonappdata}\KzDesk\device.json'), Raw) then
      WizardForm.FinishedLabel.Caption :=
        'KzDesk Host is installed and running.' + #13#10#13#10 +
        'Device ID : ' + JsonValue(String(Raw), 'displayId') + #13#10 +
        'Password  : ' + JsonValue(String(Raw), 'password') + #13#10#13#10 +
        'Enter both in the KzDesk Remote app (Device ID + Password). You can view them again from the Start Menu. Updates install automatically.';
end;
