/*
 KzDesk Remote - push notifications (APNs registration)

 Copyright 2026 KazamaDev

 This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one at
 http://mozilla.org/MPL/2.0/.
 */

#import "KzPush.h"
#import <UIKit/UIKit.h>
#import <UserNotifications/UserNotifications.h>

static NSString *const kKzApiBase = @"https://kzdesk.kazamasite.biz.id";
static NSString *const kKzPushToken = @"push.token";
static NSString *const kKzPushEnabled = @"push.enabled";

@interface KzPush () <UNUserNotificationCenterDelegate>
@end

@implementation KzPush

+ (instancetype)shared
{
	static KzPush *instance = nil;
	static dispatch_once_t once;
	dispatch_once(&once, ^{
	  instance = [[KzPush alloc] init];
	});
	return instance;
}

+ (void)configure
{
	[[UNUserNotificationCenter currentNotificationCenter] setDelegate:[self shared]];
	if ([[NSUserDefaults standardUserDefaults] boolForKey:kKzPushEnabled])
	{
		dispatch_async(dispatch_get_main_queue(), ^{
		  [[UIApplication sharedApplication] registerForRemoteNotifications];
		});
	}
}

+ (void)setEnabled:(BOOL)enabled
{
	UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
	[center setDelegate:[self shared]];
	if (enabled)
	{
		[center requestAuthorizationWithOptions:(UNAuthorizationOptionAlert | UNAuthorizationOptionSound |
		                                         UNAuthorizationOptionBadge)
		                      completionHandler:^(BOOL granted, NSError *_Nullable error) {
		                        dispatch_async(dispatch_get_main_queue(), ^{
			                      if (granted)
				                      [[UIApplication sharedApplication] registerForRemoteNotifications];
			                      else
				                      [[NSUserDefaults standardUserDefaults] setBool:NO
				                                                              forKey:kKzPushEnabled];
		                        });
		                      }];
	}
	else
	{
		NSString *token = [[NSUserDefaults standardUserDefaults] stringForKey:kKzPushToken];
		if ([token length])
			[self postJSON:@{ @"token" : token } path:@"/api/push/unregister"];
		[[NSUserDefaults standardUserDefaults] removeObjectForKey:kKzPushToken];
		[[UIApplication sharedApplication] unregisterForRemoteNotifications];
	}
}

+ (void)didRegisterWithToken:(NSData *)deviceToken
{
	const unsigned char *bytes = [deviceToken bytes];
	NSMutableString *hex = [NSMutableString stringWithCapacity:[deviceToken length] * 2];
	for (NSUInteger i = 0; i < [deviceToken length]; i++)
		[hex appendFormat:@"%02x", bytes[i]];

	[[NSUserDefaults standardUserDefaults] setObject:hex forKey:kKzPushToken];
	NSString *version =
	    [[[NSBundle mainBundle] infoDictionary] objectForKey:@"CFBundleShortVersionString"];
	NSString *lang = [[NSLocale preferredLanguages] firstObject] ?: @"id";
	[self postJSON:@{
		@"token" : hex,
		@"platform" : @"ios",
		@"lang" : [lang hasPrefix:@"en"] ? @"en" : @"id",
		@"app_version" : version ?: @"",
		@"topics" : @{ @"updates" : @YES, @"announcements" : @YES },
	}
	          path:@"/api/push/register"];
}

+ (void)postJSON:(NSDictionary *)body path:(NSString *)path
{
	NSURL *url = [NSURL URLWithString:[kKzApiBase stringByAppendingString:path]];
	NSData *data = [NSJSONSerialization dataWithJSONObject:body options:0 error:nil];
	if (!url || !data)
		return;
	NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:url];
	[request setHTTPMethod:@"POST"];
	[request setValue:@"application/json" forHTTPHeaderField:@"Content-Type"];
	[request setHTTPBody:data];
	[request setTimeoutInterval:15];
	[[[NSURLSession sharedSession]
	    dataTaskWithRequest:request
	      completionHandler:^(NSData *_Nullable d, NSURLResponse *_Nullable r, NSError *_Nullable e) {
	        if (e)
		        NSLog(@"KzPush: %@ failed: %@", path, e);
	      }] resume];
}

#pragma mark - UNUserNotificationCenterDelegate

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
       willPresentNotification:(UNNotification *)notification
         withCompletionHandler:(void (^)(UNNotificationPresentationOptions))completionHandler
{
	// Tampilkan juga saat aplikasi sedang terbuka.
	completionHandler(UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionList |
	                  UNNotificationPresentationOptionSound);
}

@end
