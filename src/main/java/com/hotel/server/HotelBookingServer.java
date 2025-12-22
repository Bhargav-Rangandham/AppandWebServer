package com.hotel.server;

import java.net.InetSocketAddress;
import java.sql.Connection;
import com.hotel.app.AppFilterHandler;
import com.hotel.app.BookingHandler;
import com.hotel.app.BookingHistoryHandler;
import com.hotel.app.HomePageHandler;
import com.hotel.app.HotelsHandler;
import com.hotel.app.LoginHandler;
import com.hotel.app.PgsHandler;
import com.hotel.app.ProfileHandler;
import com.hotel.app.RegisterHandler;
import com.hotel.app.RewardsWalletHandler;
import com.hotel.utilities.DbConfig;
import com.hotel.utilities.DbConfigLoader;
import com.hotel.web.finance.GetPartnerFinanceHandler;
import com.hotel.web.finance.GetPartnerTransactionsHandler;
import com.hotel.web.finance.RequestPayoutHandler;
import com.hotel.web.finance.SetFinanceNotificationViewedHandler;
import com.hotel.web.finance.UpdateBankDetailsHandler;
import com.hotel.web.partner.AddHotelsHandler;
import com.hotel.web.partner.AddPgHandler;
import com.hotel.web.partner.HotelImagesHandler;
import com.hotel.web.partner.WebBookingHandler;
import com.hotel.web.partner.WebDashBoardHandler;
import com.hotel.web.partner.WebLoginRegisterHandler;
import com.hotel.web.partner.WebProfileHandler;
import com.hotel.web.partner.WebViewHotelsHandler;
import com.hotel.web.partner.WebViewPGsHandler;
import com.sun.net.httpserver.HttpServer;

public class HotelBookingServer {
    public static void main(String[] args) throws Exception {
        int port = 8080; // Keep consistent with Flutter (10.0.2.2:8080)

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        
        // ===== Load DB config once at startup =====
        String configPath = System.getProperty("config.path");

        if (configPath == null || configPath.isBlank()) {
            throw new IllegalStateException(
                    "Missing JVM argument: -Dconfig.path=/full/path/to/db.properties"
            );
        }

        DbConfig dbConfig = DbConfigLoader.load(configPath);

        // ===== Validate pooled connections =====
        try (Connection customerConn =
                     dbConfig.getCustomerDataSource().getConnection();
             Connection partnerConn =
                     dbConfig.getPartnerDataSource().getConnection()) {
            // Just validation
        }

        // ========== MOBILE / APP HANDLERS ==========
        server.createContext("/login", new LoginHandler(dbConfig));
        server.createContext("/app/forgot-password/verify", new LoginHandler(dbConfig));
        server.createContext("/app/forgot-password/change", new LoginHandler(dbConfig));
        server.createContext("/register", new RegisterHandler(dbConfig));
        server.createContext("/hotels/filter", new HomePageHandler(dbConfig));
        server.createContext("/hotels", new HotelsHandler(dbConfig));
        server.createContext("/paying_guest", new PgsHandler(dbConfig));
        server.createContext("/booking", new BookingHandler(dbConfig));
        server.createContext("/profile", new ProfileHandler(dbConfig));
        server.createContext("/booking-history", new BookingHistoryHandler(dbConfig));
        server.createContext("/cancel-booking", new BookingHistoryHandler(dbConfig));
        server.createContext("/update-booking-dates", new BookingHistoryHandler(dbConfig));
        server.createContext("/filterHotels", new AppFilterHandler(dbConfig));
        
        server.createContext("/wallet", new RewardsWalletHandler(dbConfig));
        server.createContext("/wallet/deposit", new RewardsWalletHandler(dbConfig));
        server.createContext("/wallet/pay", new RewardsWalletHandler(dbConfig));
        server.createContext("/coupon/validate", new RewardsWalletHandler(dbConfig));
        server.createContext("/referrals", new RewardsWalletHandler(dbConfig));

        // ========== WEB HANDLERS ==========
        server.createContext("/weblogin", new WebLoginRegisterHandler(dbConfig));
        server.createContext("/registerlogin", new WebLoginRegisterHandler(dbConfig));
        server.createContext("/forgotpassword", new WebLoginRegisterHandler(dbConfig));
        
        server.createContext("/api/partner", new WebDashBoardHandler(dbConfig));

        server.createContext("/webgetprofile", new WebProfileHandler(dbConfig));
        server.createContext("/webupdateprofile", new WebProfileHandler(dbConfig));
        server.createContext("/webchangepassword", new WebProfileHandler(dbConfig));
        server.createContext("/webdeleteprofile", new WebProfileHandler(dbConfig));

        server.createContext("/webaddhotels", new AddHotelsHandler(dbConfig));
        server.createContext("/hotel_images", new HotelImagesHandler(dbConfig));
        server.createContext("/webaddpgs", new AddPgHandler(dbConfig));

        server.createContext("/webviewhotels", new WebViewHotelsHandler(dbConfig)); 
        server.createContext("/webviewpgs", new WebViewPGsHandler(dbConfig));

        server.createContext("/webgetPartnerBookings", new WebBookingHandler(dbConfig));
        server.createContext("/webcancelBooking", new WebBookingHandler(dbConfig));
        server.createContext("/webupdateBookingStatus", new WebBookingHandler(dbConfig));
        server.createContext("/setNotificationViewed", new SetFinanceNotificationViewedHandler(dbConfig));



        // ========== CUSTOMIZATION ==========
        server.createContext("/customize", new ProfileHandler(dbConfig));

        // ========== PARTNER FINANCE HANDLERS ==========
        server.createContext("/getPartnerFinance", new GetPartnerFinanceHandler(dbConfig));
        server.createContext("/updateBankDetails", new UpdateBankDetailsHandler(dbConfig));
        server.createContext("/requestPayout", new RequestPayoutHandler(dbConfig));
        server.createContext("/getPartnerTransactions", new GetPartnerTransactionsHandler(dbConfig));
        

        // ======== START SERVER ========
        server.setExecutor(null); // default executor
        server.start();

        System.out.println("✅ Server started successfully on port " + port);
        System.out.println("Available endpoints:");

    }
}
