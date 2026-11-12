#!/bin/sh
# Construit gestionnaire-telechargement_1.0_all.deb à partir de ./paquet
fakeroot dpkg-deb --build paquet gestionnaire-telechargement_1.0_all.deb
