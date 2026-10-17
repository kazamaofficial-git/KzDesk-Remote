/*
 KzDesk Remote - push notifications (APNs registration)

 Copyright 2026 KazamaDev

 This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one at
 http://mozilla.org/MPL/2.0/.
 */

#import <Foundation/Foundation.h>

/**
 Mendaftarkan perangkat ke server KzDesk untuk notifikasi push (rilis baru dan pengumuman).
 Opt-in: tidak ada permintaan izin sebelum pengguna menyalakan "Push Notifications".
 */
@interface KzPush : NSObject

/// Dipanggil saat aplikasi diluncurkan: memasang delegate dan mendaftar ulang bila sudah diaktifkan.
+ (void)configure;

/// Menyalakan/mematikan push. Menyalakan akan meminta izin notifikasi iOS.
+ (void)setEnabled:(BOOL)enabled;

/// Diteruskan dari -application:didRegisterForRemoteNotificationsWithDeviceToken:.
+ (void)didRegisterWithToken:(NSData *)deviceToken;

@end
