' Adds the octo:// URL scheme to an Octo MSI, for the user who installs it,
' so a family link's "Open in Octo" opens the app from the first moment, and
' the keys go with it when Octo is removed. Octo writes the same keys itself
' when it runs from somewhere new (system/LinkRegistration.kt).
'
' cscript //nologo add-url-scheme.vbs <path to the .msi>
'
' Writes one component under the install folder, with the scheme's registry
' keys (HKCU, as the MSI installs per user) and the first as its key path,
' and puts it in the main feature. Running it twice changes nothing more.

Option Explicit
Dim installer, db, view, record, msi
msi = WScript.Arguments(0)
Set installer = CreateObject("WindowsInstaller.Installer")
Set db = installer.OpenDatabase(msi, 1) ' transact

Function Has(sql)
  Set view = db.OpenView(sql)
  view.Execute
  Set record = view.Fetch
  Has = Not (record Is Nothing)
  view.Close
End Function

Sub Run(sql)
  Set view = db.OpenView(sql)
  view.Execute
  view.Close
End Sub

If Not Has("SELECT `Component` FROM `Component` WHERE `Component` = 'OctoUrlScheme'") Then
  ' Attributes 4: the key path is a registry value.
  Run "INSERT INTO `Component` (`Component`, `ComponentId`, `Directory_`, `Attributes`, `KeyPath`) VALUES ('OctoUrlScheme', '{4671120F-1428-548A-A515-2ECBC7C90F74}', 'INSTALLDIR', 4, 'OctoUrlKey')"
  ' Root 1 is HKEY_CURRENT_USER. "#%" with nothing after it is an empty
  ' string, which is all "URL Protocol" needs to be.
  Run "INSERT INTO `Registry` (`Registry`, `Root`, `Key`, `Name`, `Value`, `Component_`) VALUES ('OctoUrlKey', 1, 'Software\Classes\octo', '', 'URL:Octo', 'OctoUrlScheme')"
  Run "INSERT INTO `Registry` (`Registry`, `Root`, `Key`, `Name`, `Value`, `Component_`) VALUES ('OctoUrlProtocol', 1, 'Software\Classes\octo', 'URL Protocol', '#%', 'OctoUrlScheme')"
  Run "INSERT INTO `Registry` (`Registry`, `Root`, `Key`, `Name`, `Value`, `Component_`) VALUES ('OctoUrlIcon', 1, 'Software\Classes\octo\DefaultIcon', '', '""[INSTALLDIR]Octo.exe"",0', 'OctoUrlScheme')"
  Run "INSERT INTO `Registry` (`Registry`, `Root`, `Key`, `Name`, `Value`, `Component_`) VALUES ('OctoUrlOpen', 1, 'Software\Classes\octo\shell\open\command', '', '""[INSTALLDIR]Octo.exe"" ""%1""', 'OctoUrlScheme')"
  Run "INSERT INTO `FeatureComponents` (`Feature_`, `Component_`) VALUES ('DefaultFeature', 'OctoUrlScheme')"
  db.Commit
End If
WScript.Echo "octo:// is registered by " & msi
