/**
 * FreeRDP: A Remote Desktop Protocol Implementation
 * Android JNI Callback Helpers
 *
 * Copyright 2011-2013 Thincast Technologies GmbH, Author: Martin Fleisz
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

#include <freerdp/config.h>

#include <stdio.h>

#include "android_jni_callback.h"
#include "android_freerdp_jni.h"

#include <freerdp/log.h>
#define TAG CLIENT_TAG("android.callback")

static JavaVM* jVM;
static jobject jLibFreeRDPObject;

static const char* jLibFreeRDPPath = JAVA_LIBFREERDP_CLASS;

static void report_callback_problem(JNIEnv* env, jclass clazz, const char* callback,
                                   const char* reason)
{
	if (!env || !clazz)
		return;
	char message[256] = { 0 };
	(void)snprintf(message, sizeof(message), "FreeRDP JNI callback %s: %s", callback,
	               reason ? reason : "Java exception");
	WLog_ERR(TAG, "%s", message);
	jmethodID method = (*env)->GetStaticMethodID(env, clazz, "OnNativeCallbackException",
	                                             "(Ljava/lang/String;)V");
	if (!method)
	{
		if ((*env)->ExceptionCheck(env))
			(*env)->ExceptionClear(env);
			return;
	}
	jstring text = (*env)->NewStringUTF(env, message);
	if (text)
	{
		(*env)->CallStaticVoidMethod(env, clazz, method, text);
		(*env)->DeleteLocalRef(env, text);
	}
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionClear(env);
}

static void log_and_clear_exception(JNIEnv* env, jclass clazz, const char* callback)
{
	if (!env || !(*env)->ExceptionCheck(env))
		return;
	jthrowable error = (*env)->ExceptionOccurred(env);
	(*env)->ExceptionDescribe(env);
	(*env)->ExceptionClear(env);
	char detail[384] = "Java exception (stack in logcat)";
	if (error)
	{
		jclass errorClass = (*env)->GetObjectClass(env, error);
		jmethodID toString = errorClass ? (*env)->GetMethodID(env, errorClass, "toString",
		                                                      "()Ljava/lang/String;") : nullptr;
		jstring text = toString ? (jstring)(*env)->CallObjectMethod(env, error, toString) : nullptr;
		if (text)
		{
			const char* chars = (*env)->GetStringUTFChars(env, text, nullptr);
			if (chars)
			{
				(void)snprintf(detail, sizeof(detail), "%s", chars);
				(*env)->ReleaseStringUTFChars(env, text, chars);
			}
			(*env)->DeleteLocalRef(env, text);
		}
		if ((*env)->ExceptionCheck(env))
			(*env)->ExceptionClear(env);
		if (errorClass)
			(*env)->DeleteLocalRef(env, errorClass);
		(*env)->DeleteLocalRef(env, error);
	}
	WLog_ERR(TAG, "Java exception while invoking callback %s: %s", callback, detail);
	report_callback_problem(env, clazz, callback, detail);
}

static void jni_load_class(JNIEnv* env, const char* path, jobject* objptr)
{
	jclass class;
	jmethodID method;
	jobject object;
	WLog_DBG(TAG, "jni_load_class: %s", path);
	class = (*env)->FindClass(env, path);

	if (!class)
	{
		WLog_ERR(TAG, "jni_load_class: failed to find class %s", path);
		goto finish;
	}

	method = (*env)->GetMethodID(env, class, "<init>", "()V");

	if (!method)
	{
		WLog_ERR(TAG, "jni_load_class: failed to find class constructor of %s", path);
		goto finish;
	}

	object = (*env)->NewObject(env, class, method);

	if (!object)
	{
		WLog_ERR(TAG, "jni_load_class: failed create new object of %s", path);
		goto finish;
	}

	(*objptr) = (*env)->NewGlobalRef(env, object);
finish:

	while (0)
		;
}

jint init_callback_environment(JavaVM* vm, JNIEnv* env)
{
	jVM = vm;
	jni_load_class(env, jLibFreeRDPPath, &jLibFreeRDPObject);
	return JNI_VERSION_1_6;
}

/* attach current thread to jvm */
jboolean jni_attach_thread(JNIEnv** env)
{
	if ((*jVM)->GetEnv(jVM, (void**)env, JNI_VERSION_1_4) != JNI_OK)
	{
		WLog_DBG(TAG, "android_java_callback: attaching current thread");
		(*jVM)->AttachCurrentThread(jVM, env, nullptr);

		if ((*jVM)->GetEnv(jVM, (void**)env, JNI_VERSION_1_4) != JNI_OK)
		{
			WLog_ERR(TAG, "android_java_callback: failed to obtain current JNI environment");
		}

		return JNI_TRUE;
	}

	return JNI_FALSE;
}

/* attach current thread to JVM */
void jni_detach_thread()
{
	(*jVM)->DetachCurrentThread(jVM);
}

/* callback with void result */
static void java_callback_void(jobject obj, const char* callback, const char* signature,
                               va_list args)
{
	jboolean attached;
	JNIEnv* env = nullptr;
	jclass clazz = nullptr;
	jmethodID method = nullptr;
	if (!obj || !jVM)
		return;
	attached = jni_attach_thread(&env);
	if (!env)
		return;
	clazz = (*env)->GetObjectClass(env, obj);
	if (clazz)
		method = (*env)->GetStaticMethodID(env, clazz, callback, signature);
	if (!method)
	{
		log_and_clear_exception(env, clazz, callback);
		report_callback_problem(env, clazz, callback, "static Java method was not found");
		goto finish;
	}
	(*env)->CallStaticVoidMethodV(env, clazz, method, args);
	log_and_clear_exception(env, clazz, callback);
finish:
	if (clazz)
		(*env)->DeleteLocalRef(env, clazz);
	if (attached == JNI_TRUE)
		jni_detach_thread();
}

/* callback with bool result */
static jboolean java_callback_bool(jobject obj, const char* callback, const char* signature,
                                   va_list args)
{
	jboolean attached;
	jboolean result = JNI_FALSE;
	JNIEnv* env = nullptr;
	jclass clazz = nullptr;
	jmethodID method = nullptr;
	if (!obj || !jVM)
		return JNI_FALSE;
	attached = jni_attach_thread(&env);
	if (!env)
		return JNI_FALSE;
	clazz = (*env)->GetObjectClass(env, obj);
	if (clazz)
		method = (*env)->GetStaticMethodID(env, clazz, callback, signature);
	if (!method)
	{
		log_and_clear_exception(env, clazz, callback);
		report_callback_problem(env, clazz, callback, "static Java method was not found");
		goto finish;
	}
	result = (*env)->CallStaticBooleanMethodV(env, clazz, method, args);
	if ((*env)->ExceptionCheck(env))
	{
		log_and_clear_exception(env, clazz, callback);
		report_callback_problem(env, clazz, callback, "Java callback threw; returning false");
		result = JNI_FALSE;
	}
finish:
	if (clazz)
		(*env)->DeleteLocalRef(env, clazz);
	if (attached == JNI_TRUE)
		jni_detach_thread();
	return result;
}

/* callback with int result */
static jint java_callback_int(jobject obj, const char* callback, const char* signature,
                              va_list args)
{
	jboolean attached;
	jint result = -1;
	JNIEnv* env = nullptr;
	jclass clazz = nullptr;
	jmethodID method = nullptr;
	if (!obj || !jVM)
		return -1;
	attached = jni_attach_thread(&env);
	if (!env)
		return -1;
	clazz = (*env)->GetObjectClass(env, obj);
	if (clazz)
		method = (*env)->GetStaticMethodID(env, clazz, callback, signature);
	if (!method)
	{
		log_and_clear_exception(env, clazz, callback);
		report_callback_problem(env, clazz, callback, "static Java method was not found");
		goto finish;
	}
	result = (*env)->CallStaticIntMethodV(env, clazz, method, args);
	if ((*env)->ExceptionCheck(env))
	{
		log_and_clear_exception(env, clazz, callback);
		report_callback_problem(env, clazz, callback, "Java callback threw; returning deny");
		result = -1;
	}
finish:
	if (clazz)
		(*env)->DeleteLocalRef(env, clazz);
	if (attached == JNI_TRUE)
		jni_detach_thread();
	return result;
}

/* callback to freerdp class */
void freerdp_callback(const char* callback, const char* signature, ...)
{
	va_list vl = WINPR_C_ARRAY_INIT;
	va_start(vl, signature);
	java_callback_void(jLibFreeRDPObject, callback, signature, vl);
	va_end(vl);
}

jboolean freerdp_callback_bool_result(const char* callback, const char* signature, ...)
{
	va_list vl = WINPR_C_ARRAY_INIT;
	va_start(vl, signature);
	jboolean res = java_callback_bool(jLibFreeRDPObject, callback, signature, vl);
	va_end(vl);
	return res;
}

jint freerdp_callback_int_result(const char* callback, const char* signature, ...)
{
	va_list vl = WINPR_C_ARRAY_INIT;
	va_start(vl, signature);
	jint res = java_callback_int(jLibFreeRDPObject, callback, signature, vl);
	va_end(vl);
	return res;
}
