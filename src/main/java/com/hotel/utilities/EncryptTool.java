package com.hotel.utilities;

public class EncryptTool {
	
	public static void main(String[] args) throws Exception {
		//String masterKey = System.getenv("CONFIG_SECRET_KEY");
		String masterKey = "";
		String plainPassword = "";

		String encrypted = CryptoUtil.encrypt(plainPassword, masterKey);
		System.out.println(encrypted);
	}

}