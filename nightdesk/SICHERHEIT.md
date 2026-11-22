# Sicherheitsarchitektur und Abnahme

## Datenpfad und Grenzen

- Android hält lokale Papierausführungen, Kurspunkte und die Watchlist in Room. Das Zugriffstoken liegt AES-GCM-verschlüsselt mit einem Schlüssel aus dem Android Keystore vor. Die APK enthält keine Börsenschlüssel. Börsenzugriffsdaten liegen in serverseitigen Secrets.
- Die HTTPS-API verlangt ein widerrufbares Betreiber-Token. Der Dienst prüft Eingabegröße, Schema, Order-ID, Kursfrische, Spread, Spot-Mindestmenge, Tickgröße, Positionsrisiko und Handelslimits. Der Reverse Proxy terminiert TLS. Die Handels-API unterstützt ausschließlich Kraken Spot und besitzt keine Finanzierungsschnittstellen.
- Live-Freigabe erfordert einen gültigen Zugriffstest und eine zeitlich begrenzte Challenge mit separatem Freigabecode. Für Bot-Orders ist ein weiterer Code erforderlich. Bei einem App-Neustart öffnet die lokale Papieransicht. Der Server kennt einen eigenen Modus und persistiert Freigaben und Pause/Not-Stopp.
- Eine manuelle Echtorder erfordert eine an Nutzdaten und Ablaufzeit gebundene Vorschau. Der Server serialisiert neue Orders, schreibt die interne ID vor dem Börsenaufruf dauerhaft ins SQLite-Journal und verwendet sie als Kraken-`cl_ord_id`. Wiederholungen mit derselben Nutzlast liefern den gespeicherten Status. Bei Timeout bleiben neue Orders blockiert, bis der Exchange-Status eindeutig geklärt ist. Kraken-Order- und Trade-IDs verknüpfen das Journal mit der Börse. Der Marktorderpreis und die endgültige Ausführungsgebühr stammen von Kraken.
- Teilausführungen werden einzeln nach Trade-ID gespeichert. AddOrder, CancelOrder, Status und Ausführungen sind getrennte Schritte. Der Server lässt gleichzeitig höchstens eine ausstehende Live-NightDesk-Order zu und blockiert neue Orders bei fremden offenen Kraken-Orders. Fremde Orders sind in der Kontoübersicht lesbar und werden im Kraken-Konto verwaltet.
- Das angenommene Positionsrisiko bezieht sich auf die rechnerische Distanz zum Stop-Niveau. Stop-Loss und Take-Profit sind Überwachungsregeln des laufenden Bots. Die App zeigt den Worker-Status mit Zeitgrenze. Bei Verbindungsproblemen wird er als unbestätigt dargestellt. Kurslücken, Volatilität und Verzögerungen können einen höheren tatsächlichen Verlust verursachen.
- Echtgeld-Kennzahlen stammen aus bestätigten Kraken-Abfragen und einsehbaren Trades. Der Kontowert umfasst bewertbare Guthaben einschließlich Kontobewegungen. Das realisierte Ergebnis in der App berücksichtigt Ausführungen mit erfasstem Einstand. Die Anzeige ist eine operative Übersicht und verwendet eine gleitende Kostenbasis.
- Ein Not-Stopp unterbindet neue Bot-Aufträge. Börsenausführungen, die bereits übermittelt wurden, bleiben unter Kontrolle von Kraken. Der Betreiber behandelt offene Börsenorders und Positionen gesondert. Serverautomatik und Android-Hintergrundbetrieb sind getrennt.

## Nachweise und offene reale Abnahme

Die automatisierten Python-Tests verwenden gemockte private Kraken-Antworten. Signierung wird gegen einen unabhängig mit OpenSSL berechneten Prüfwert getestet. Die Tests decken Papierrechnung und Persistenz, partielle Ausführungen und Gebühren, Konto- und Order-API, Berechtigungsprüfung, Bestätigung und Replay, konkurrierende Orders, Verbindungsverlust und Abgleich, Not-Stopp und Tagesverlustgrenze ab. Nutzung der öffentlichen Kraken-Marktdaten lässt sich gesondert ohne Schlüssel prüfen.

Mit einem echten Kraken-Konto sind folgende Funktionen **End-to-End unbestätigt**:

- Reale Schlüsselrechte, regionale Marktverfügbarkeit und verfügbares EUR-/Coin-Guthaben.
- Ausführung echter Markt- und Limitorders, aktuelle Gebühren, Teilfüllungen und Storno.
- Wiederaufnahme eines tatsächlichen Netzwerkabbruchs mit korrekten Exchange-IDs.
- Durchgehender Bot-Betrieb mit echtem Konto und kontrolliertem Not-Stopp.
- Signierte Echtgeld-APK auf einem Samsung Galaxy S26 Ultra.

Der Debug-Build, der App-Laufzeittest und der private Kraken-End-to-End-Test sind eigenständige Prüfungen. Die Freigabe eines Papier-Testbuilds bestätigt ausschließlich die tatsächlich ausgeführten Prüfungen.
