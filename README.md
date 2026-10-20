# Executive Black v0.5 – NFC-Prototyp

Weiterentwicklung der bestehenden Android-App im privaten BMW-inspirierten Design. Das Originalkartenbild `app/src/main/assets/card-front.jpg` bleibt unverändert. Keine offizielle BMW-, Revolut-, Google- oder Mastercard-App.

## Was funktioniert
- Executive Black Kartenansicht inkl. Vollbildmodus und NFC-Hardwarestatus.
- Echte Android Host Card Emulation (HCE) mit **proprietärer Test-AID F00102424D57**, Kategorie **other**.
- Test-Funkbetrieb zwischen zwei NFC-Handys: ein Gerät als HCE-Karte, ein zweites als NFC-Leser; dazu Statusanzeigen.
- Lokaler Protokoll-Selbsttest per Knopfdruck.
- Google Wallet weiterhin separat zum echten Bezahlen. Keine Ersetzung der Standard-Wallet erforderlich.

## Grenzen
**Kein echtes NFC-Bezahlen über Revolut oder Mastercard.** An einem normalen Kassenterminal wird dieses Testprotokoll nicht als gültiges Zahlungsinstrument akzeptiert. Es werden keine Kartennummern, CVVs, Bank-Zugangsdaten, Token oder Zahlungen verarbeitet. Echte Zahlungen benötigen die autorisierte Issuer-/Scheme-Tokenisierung und eine EMV-Implementierung.

## Testanleitung
1. APK installieren; NFC aktivieren.
2. In der App „NFC-Protokoll intern testen“ betätigen. Bestätigung bedeutet ausschließlich, dass die APDU-Antwort-Logik funktioniert.
3. Für den Funk-Test die gleiche APK auf **zwei NFC-Android-Handys** installieren.
4. Gerät A: Executive App öffnen; „Zweites Handy als NFC-Karte prüfen“ aktiviert Leserbetrieb.
5. Gerät B: Executive App öffnen (kein Leserbetrieb), Gerät entsperrt lassen.
6. Beide NFC-Antennen nah zusammenhalten; Gerät A muss „NFC-Test erfolgreich“ melden, Gerät B zählt die Testabfragen.
7. Leserbetrieb auf Gerät A beenden.

Das Test-AID `F00102424D57` antwortet auf SELECT-APDUs mit ASCII `BMW-HCE-DEMO` + `9000`. Keine EMV-Bankkarten-AIDs sind registriert.

## Installation
GitHub Actions > **Build Android Debug APK** > letzter erfolgreicher Build > Artifact `executive-black-debug-apk` laden, ZIP entpacken und `app-debug.apk` installieren. Die APK ist eine Debug-Testversion und wurde noch nicht auf dem echten Samsung geprüft.
