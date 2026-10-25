# Executive Black v0.9 – BMW-Karte vorne, Bankkarte im Hintergrund

Private Android-App im BMW-Executive-Design. Beim Öffnen erscheint die BMW-Karte im Vollbild. Bezahlt wird am Terminal mit der echten Bankkarte (z. B. Revolut Mastercard), die in **Google Wallet** hinterlegt ist – Google Wallet arbeitet dabei im Hintergrund.

## So funktioniert es
1. Executive Black öffnen (oder Schnelleinstellungs-Kachel **Executive Card**).
2. Handy entsperrt ans Terminal halten.
3. Android leitet die NFC-Anfrage an die Standard-Zahlungs-App Google Wallet weiter. Google Wallet blendet kurz seine Bestätigung ein, danach ist wieder die BMW-Karte zu sehen.

Die App zeigt live an, ob alles bereit ist (NFC an, Displaysperre, Google Wallet installiert, Google Wallet als Standard – sofern Android das preisgibt) und führt mit je einem Knopf zur passenden Einstellung.

## Grenzen (technisch, nicht lösbar per Code)
- Eine eigene Bezahlfunktion ohne Google Wallet bräuchte eine Mastercard-Tokenisierung (MDES) und eine Freigabe durch die Bank. Revolut stellt Karten nur in Google Wallet, Samsung Wallet und der eigenen App bereit.
- Die kurze Bestätigung von Google Wallet nach der Zahlung kann keine App unterdrücken.
- Die App sieht keine Zahlung und speichert keine Kartendaten. Sie enthält seit v0.9 keinen NFC-Kartenemulations-Dienst mehr und kann Google Wallet deshalb nicht stören.

## Download
Jeder Push baut eine APK und veröffentlicht sie als Release **dev-latest**:
https://github.com/GoldenUnicorn24/Bmw/releases/tag/dev-latest

Privates Designkonzept, nicht mit BMW, Google oder Revolut verbunden.
