#!/bin/sh
# Construit gestionnaire-telechargement_1.2_all.deb à partir de ./paquet
fakeroot dpkg-deb --build paquet gestionnaire-telechargement_1.2_all.deb
