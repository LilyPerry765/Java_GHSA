package com.cavisson.jenkins;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class BaseConnection {
	private transient final static Logger logger = Logger.getLogger(BaseConnection.class.getName());

	private static HostnameVerifier getSkipHostCheckVerifier() {
		if (skipHostCheckVerifier == null) {
			skipHostCheckVerifier = new HostnameVerifier() {

				@Override
				public boolean verify(String hostname, SSLSession session) {
					return true;
				}
			};

		}

		return skipHostCheckVerifier;
	}

	private static SSLSocketFactory skipSSLVerficationSSLFactory = null;
	private static HostnameVerifier skipHostCheckVerifier = null;

	// TODO: check if need to put inside syncro. block
	private static SSLSocketFactory getSkipSSLVerficationSSLCtx() {
		if (skipSSLVerficationSSLFactory == null) {
			TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {

				@Override
				public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
					// TODO Auto-generated method stub

				}

				@Override
				public X509Certificate[] getAcceptedIssuers() {
					// TODO Auto-generated method stub
					return null;
				}

				@Override
				public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
					// TODO Auto-generated method stub

				}
			} };
			SSLContext sslContext = null;
			try {
				sslContext = SSLContext.getInstance("TLS");
			} catch (NoSuchAlgorithmException e) {
				logger.log(Level.SEVERE, "Cavisson-Plugin|NoSuchAlgorithmException  :- " + e);
			}
			try {
				if (sslContext != null) {
					sslContext.init(null, trustAllCerts, new SecureRandom());
					skipSSLVerficationSSLFactory = sslContext.getSocketFactory();
				}
			} catch (KeyManagementException e) {
				logger.log(Level.SEVERE, "Cavisson-Plugin|KeyManagementException exception :-", e);
			}
		}

		return skipSSLVerficationSSLFactory;
	}

	public static synchronized URLConnection getConnections(URL url, boolean skipSSLCertValidation,
			boolean skipSSLHostValidation) {
		// check if it is http or https.
		URLConnection urlConnection = null;
		try {
			urlConnection = url.openConnection();

			if (urlConnection instanceof HttpsURLConnection) {
				if (skipSSLCertValidation) {
					((HttpsURLConnection) urlConnection).setSSLSocketFactory(getSkipSSLVerficationSSLCtx());
				}

				if (skipSSLHostValidation) {
					((HttpsURLConnection) urlConnection).setHostnameVerifier(getSkipHostCheckVerifier());
				}
			}
		} catch (IOException e) {
			logger.log(Level.SEVERE, "Cavisson-Plugin|IOException exception :-", e);
			return urlConnection;
		}
		return urlConnection;
	}

}