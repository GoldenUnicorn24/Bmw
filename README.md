# Executive Black Wallet v0.5 – NFC-Labormodus

Privates, nicht mit BMW, Revolut, Google oder Mastercard verbundenes Designprojekt. Das originale, vom Nutzer bereitgestellte BMW-Kartenbild bleibt in der App unverändert.

## Enthalten
- Native Android-App mit BMW-Kartenbild, Kartenrotation und Präsentationsmodus
- NFC-Hardwarestatus und Google-Wallet-Verknüpfung
- Echte Android Host Card Emulation (HCE), aber ausschließlich für **proprietäres Test-AID `F0112233445566`**
- Anzeige, ob die App Androids Standard-Wallet ist, und ob ein passender Test-NFC-Reader die Test-AID angesprochen hat
- Keine Bankkartendaten, kein CVV, kein Mastercard-AID, keine fingierten Zahlungstoken

## Wichtig: keine Zahlungsfähigkeit
Diese Test-App kann an regulären Kartenterminals keine Revolut- oder Mastercard-Zahlung ausführen. Die Registrierung als NFC-Wallet bei Android ist **keine** Autorisierung durch Kartenissuer oder Zahlungssysteme. Eine echte Kontaktloszahlung erfordert offizielle Issuer-/Scheme-Tokenisierung samt EMV-Verarbeitung. Keinesfalls als Beleg einer akzeptierten Zahlung interpretieren.

### Test der Android-Wallet-Erkennung
1. APK installieren, NFC einschalten, App öffnen.
2. „Als Test-Wallet auswählen“ aufrufen. Wenn Android dies erlaubt, bestätigt es nur eine Wallet-Rolle.
3. Falls du Google Wallet weiterhin zum Bezahlen nutzt, **Google Wallet als Standard belassen**.
4. Der HCE-Protokolltest erfordert einen passenden NFC-Reader, der `00A4040007F0112233445566` sendet. Erwartete Antwort: `6F0B8407F0112233445566A5009000`. Die App zählt solche Testlesevorgänge.
5. Sollte Executive Black als Standard gesetzt worden sein, stelle Google Wallet vor echten Einkäufen in den Android-Einstellungen wieder als Standard her.

GitHub Actions: Build Android Debug APK → `executive-black-debug-apk`. Noch kein Test auf dem echten Samsung erfolgt.
