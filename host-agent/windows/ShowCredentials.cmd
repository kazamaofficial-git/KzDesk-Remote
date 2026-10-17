@echo off
rem Buka jendela ID + password dengan hak Administrator (file data hanya bisa dibaca Administrator).
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process powershell -Verb RunAs -WindowStyle Hidden -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-WindowStyle','Hidden','-File','%~dp0KzDeskShowCredentials.ps1'"
