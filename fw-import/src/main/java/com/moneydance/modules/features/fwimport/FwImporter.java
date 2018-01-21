/*
 * Created on Dec 16, 2017
 */
package com.moneydance.modules.features.fwimport;

import static com.johns.swing.util.HTMLPane.CL_DECREASE;
import static com.johns.swing.util.HTMLPane.CL_INCREASE;
import static java.math.RoundingMode.HALF_EVEN;
import static java.time.format.FormatStyle.MEDIUM;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.ResourceBundle;

import com.infinitekind.moneydance.model.Account;
import com.infinitekind.moneydance.model.AccountBook;
import com.infinitekind.moneydance.model.CurrencySnapshot;
import com.infinitekind.moneydance.model.CurrencyTable;
import com.infinitekind.moneydance.model.CurrencyType;
import com.johns.moneydance.util.MdUtil;
import com.johns.moneydance.util.MduException;
import com.johns.moneydance.util.SecurityHandler;
import com.johns.moneydance.util.SecurityHandlerCollector;

/**
 * Module used to import Fidelity NetBenefits workplace account data into
 * Moneydance.
 */
public class FwImporter implements SecurityHandlerCollector {
	private FwImportWindow importWindow;
	private Locale locale;
	private Account root;
	private CurrencyTable securities;

	private List<SecurityHandler> priceChanges = new ArrayList<>();
	private int numPricesSet = 0;
	private Map<String, String> csvRowMap = new LinkedHashMap<>();
	private Properties fwImportProps = null;
	private ResourceBundle msgBundle = null;

	private static final String propertiesFileName = "fw-import.properties";
	private static final String baseMessageBundleName = "com.moneydance.modules.features.fwimport.FwImportMessages";
	private static final char DOUBLE_QUOTE = '"';
	private static final DateTimeFormatter dateFmt = DateTimeFormatter.ofLocalizedDate(MEDIUM);
	private static final int PRICE_FRACTION_DIGITS = 6;

	/**
	 * Sole constructor.
	 *
	 * @param importWindow
	 * @param accountBook Moneydance account book
	 */
	public FwImporter(FwImportWindow importWindow, AccountBook accountBook) {
		this.importWindow = importWindow;
		this.locale = importWindow.getLocale();
		this.root = accountBook.getRootAccount();
		this.securities = accountBook.getCurrencies();

	} // end (FwImportWindow, AccountBook) constructor

	/**
	 * Import the selected comma separated value file.
	 */
	public void importFile() throws MduException {
		if (this.importWindow.getMarketDate() == null) {
			// Market date must be specified.
			writeFormatted("FWIMP00");
		} else {
			// Importing data for %s from file %s.
			writeFormatted("FWIMP01", this.importWindow.getMarketDate().format(dateFmt),
				this.importWindow.getFileToImport().getName());

			BufferedReader reader = openFile();
			if (reader == null)
				return; // nothing to import

			try {
				String[] header = readLine(reader);

				while (hasMore(reader)) {
					String[] values = readLine(reader);

					if (header != null && values != null) {
						this.csvRowMap.clear();

						for (int i = 0; i < header.length; ++i) {
							if (i < values.length) {
								this.csvRowMap.put(header[i], values[i]);
							} else {
								this.csvRowMap.put(header[i], "");
							}
						} // end for

						importRow();
					}
				} // end while
			} finally {
				close(reader);
			}
			if (!isModified()) {
				// No new price data found in %s.
				writeFormatted("FWIMP08", this.importWindow.getFileToImport().getName());
			}
		}

	} // end importFile()

	/**
	 * Import this row of the comma separated value file.
	 */
	private void importRow() throws MduException {
		Account account = MdUtil.getSubAccountByInvestNumber(this.root,
			stripQuotes("col.account.num"));

		if (account == null) {
			// Unable to obtain Moneydance investment account with number [%s].
			writeFormatted("FWIMP05", stripQuotes("col.account.num"));
		}
		CurrencyType security = this.securities
			.getCurrencyByTickerSymbol(stripQuotes("col.ticker"));

		if (security == null) {
			verifyAccountBalance(account);
		} else {
			BigDecimal shares = new BigDecimal(stripQuotes("col.shares"));

			storePriceQuoteIfDiff(security, shares);

			verifyShareBalance(account, security.getName(), shares);
		}

	} // end importRow()

	/**
	 * @param security the Moneydance security to use
	 * @param shares the number of shares included in the value
	 */
	private void storePriceQuoteIfDiff(CurrencyType security, BigDecimal shares)
			throws MduException {
		BigDecimal price = new BigDecimal(stripQuotes("col.price"));
		BigDecimal value = new BigDecimal(stripQuotes("col.value"));

		// see if shares * price = value to 2 places past the decimal point
		if (!shares.multiply(price).setScale(value.scale(), HALF_EVEN).equals(value)) {
			// no, so get the price to the sixth place past the decimal point
			price = value.divide(shares, PRICE_FRACTION_DIGITS, HALF_EVEN);
		}
		NumberFormat priceFmt = getCurrencyFormat(price);
		int importDate = MdUtil.convLocalToDateInt(this.importWindow.getMarketDate());
		CurrencySnapshot latestSnapshot = MdUtil.getLatestSnapshot(security);
		MdUtil.validateCurrentUserRate(security, latestSnapshot, priceFmt);
		double newPrice = price.doubleValue();
		double oldPrice = MdUtil.convRateToPrice(importDate < latestSnapshot.getDateInt()
			? security.getUserRateByDateInt(importDate)
			: latestSnapshot.getUserRate());

		if (importDate != latestSnapshot.getDateInt() || newPrice != oldPrice) {
			// Change %s price from %s to %s (<span class="%s">%+.2f%%</span>).
			String spanCl = newPrice < oldPrice ? CL_DECREASE
				: newPrice > oldPrice ? CL_INCREASE : "";
			writeFormatted("FWIMP03", security.getName(), priceFmt.format(oldPrice),
				priceFmt.format(newPrice), spanCl, (newPrice / oldPrice - 1) * 100);

			new SecurityHandler(security, this).storeNewPrice(newPrice, importDate);
			++this.numPricesSet;
		}

	} // end storePriceQuoteIfDiff(CurrencyType, BigDecimal)

	/**
	 * @param account
	 */
	private void verifyAccountBalance(Account account) throws MduException {
		if (account != null) {
			BigDecimal importedBalance = new BigDecimal(stripQuotes("col.value"));
			double balance = MdUtil.getCurrentBalance(account);

			if (importedBalance.doubleValue() != balance) {
				// Found a different balance in account %s: have %s, imported %s.
				// Note: No Moneydance security for ticker symbol [%s] (%s).
				NumberFormat cf = getCurrencyFormat(importedBalance);
				writeFormatted("FWIMP02", account.getAccountName(), cf.format(balance),
					cf.format(importedBalance), stripQuotes("col.ticker"),
					stripQuotes("col.name"));
			}
		}

	} // end verifyAccountBalance(Account)

	/**
	 * @param account
	 * @param securityName
	 * @param importedShares
	 */
	private void verifyShareBalance(Account account, String securityName,
			BigDecimal importedShares) {
		if (account != null) {
			Account secAccount = MdUtil.getSubAccountByName(account, securityName);

			if (secAccount == null) {
				// Unable to obtain Moneydance security [%s] in account %s.
				writeFormatted("FWIMP06", securityName, account.getAccountName());
			} else {
				double balance = MdUtil.getCurrentBalance(secAccount);

				if (importedShares.doubleValue() != balance) {
					// Found a different %s share balance in account %s: have %s, imported %s.
					NumberFormat nf = getNumberFormat(importedShares);
					writeFormatted("FWIMP04", secAccount.getAccountName(),
						account.getAccountName(), nf.format(balance), nf.format(importedShares));
				}
			}
		}

	} // end verifyShareBalance(Account, String, BigDecimal)

	/**
	 * @param propKey property key for column header
	 * @return value from the csv row map with any surrounding double quotes removed
	 */
	private String stripQuotes(String propKey) throws MduException {
		String csvColumnKey = getFwImportProps().getProperty(propKey);
		String val = this.csvRowMap.get(csvColumnKey);
		if (val == null) {
			// Unable to locate column %s (%s) in %s. Found columns %s
			throw asException(null, "FWIMP11", csvColumnKey, propKey,
				this.importWindow.getFileToImport(), this.csvRowMap.keySet());
		}
		int quoteLoc = val.indexOf(DOUBLE_QUOTE);

		if (quoteLoc == 0) {
			// starts with a double quote
			quoteLoc = val.lastIndexOf(DOUBLE_QUOTE);

			if (quoteLoc == val.length() - 1) {
				// also ends with a double quote => remove them
				val = val.substring(1, quoteLoc);
			}
		}

		return val.trim();
	} // end stripQuotes(String)

	/**
	 * @return a buffered reader to read from the file selected to import
	 */
	private BufferedReader openFile() {
		BufferedReader reader = null;
		try {
			reader = new BufferedReader(new FileReader(this.importWindow.getFileToImport()));
		} catch (Exception e) {
			// Exception opening file %s. %s
			writeFormatted("FWIMP12", this.importWindow.getFileToImport(), e);
		}

		return reader;
	} // end openFile()

	/**
	 * @param reader
	 * @return true when the next read will not block for input, false otherwise
	 */
	private boolean hasMore(BufferedReader reader) throws MduException {
		try {

			return reader.ready();
		} catch (Exception e) {
			// Exception checking file %s.
			throw asException(e, "FWIMP13", this.importWindow.getFileToImport());
		}
	} // end hasMore(BufferedReader)

	/**
	 * @param reader
	 * @return the comma separated tokens from the next line in the file
	 */
	private String[] readLine(BufferedReader reader) throws MduException {
		try {
			String line = reader.readLine();

			return line == null ? null : line.split(",");
		} catch (Exception e) {
			// Exception reading from file %s.
			throw asException(e, "FWIMP14", this.importWindow.getFileToImport());
		}
	} // end readLine(BufferedReader)

	/**
	 * Close the specified reader, ignoring any exceptions.
	 *
	 * @param reader
	 */
	private static void close(BufferedReader reader) {
		try {
			reader.close();
		} catch (Exception e) { /* ignore */ }

	} // end close(BufferedReader)

	/**
	 * Add a security handler to our collection.
	 *
	 * @param handler
	 */
	public void addHandler(SecurityHandler handler) {
		this.priceChanges.add(handler);

	} // end addHandler(SecurityHandler)

	/**
	 * Commit any changes to Moneydance.
	 */
	public void commitChanges() {
		for (SecurityHandler sHandler : this.priceChanges) {
			sHandler.applyUpdate();
		}
		// Changed %d security price%s.
		writeFormatted("FWIMP07", this.numPricesSet, this.numPricesSet == 1 ? "" : "s");

		forgetChanges();

	} // end commitChanges()

	/**
	 * Clear out any pending changes.
	 */
	public void forgetChanges() {
		this.priceChanges.clear();
		this.numPricesSet = 0;

	} // end forgetChanges()

	/**
	 * @return true when we have uncommitted changes in memory
	 */
	public boolean isModified() {

		return !this.priceChanges.isEmpty();
	} // end isModified()

	/**
	 * Release any resources we acquired.
	 *
	 * @return null
	 */
	public FwImporter releaseResources() {
		// nothing to release

		return null;
	} // end releaseResources()

	/**
	 * @return our properties
	 */
	private Properties getFwImportProps() throws MduException {
		if (this.fwImportProps == null) {
			InputStream propsStream = getClass().getClassLoader()
				.getResourceAsStream(propertiesFileName);
			if (propsStream == null)
				// Unable to find %s on the class path.
				throw asException(null, "FWIMP15", propertiesFileName);

			this.fwImportProps = new Properties();
			try {
				this.fwImportProps.load(propsStream);
			} catch (Exception e) {
				this.fwImportProps = null;

				// Exception loading %s.
				throw asException(e, "FWIMP16", propertiesFileName);
			} finally {
				try {
					propsStream.close();
				} catch (Exception e) { /* ignore */ }
			}
		}

		return this.fwImportProps;
	} // end getFwImportProps()

	/**
	 * @return our message bundle
	 */
	private ResourceBundle getMsgBundle() {
		if (this.msgBundle == null) {
			this.msgBundle = MdUtil.getMsgBundle(baseMessageBundleName, this.locale);
		}

		return this.msgBundle;
	} // end getMsgBundle()

	/**
	 * @param cause Exception that caused this (null if none)
	 * @param key The resource bundle key (or message)
	 * @param params Optional parameters for the detail message
	 */
	private MduException asException(Throwable cause, String key, Object... params) {

		return new MduException(cause, retrieveMessage(key), params);
	} // end asException(Throwable, String, Object...)

	/**
	 * @param key The resource bundle key (or message)
	 * @return message for this key
	 */
	private String retrieveMessage(String key) {
		try {

			return getMsgBundle().getString(key);
		} catch (Exception e) {
			// just use the key when not found
			return key;
		}
	} // end retrieveMessage(String)

	/**
	 * @param key The resource bundle key (or message)
	 * @param params Optional array of parameters for the message
	 */
	private void writeFormatted(String key, Object... params) {
		this.importWindow.addText(String.format(this.locale, retrieveMessage(key), params));

	} // end writeFormatted(String, Object...)

	/**
	 * @param amount
	 * @return a currency number format with the number of fraction digits in amount
	 */
	private NumberFormat getCurrencyFormat(BigDecimal amount) {
		DecimalFormat formatter = (DecimalFormat) NumberFormat.getCurrencyInstance(this.locale);
		formatter.setMinimumFractionDigits(amount.scale());

		return formatter;
	} // end getCurrencyFormat(BigDecimal)

	/**
	 * @param value
	 * @return a number format with the number of fraction digits in value
	 */
	private NumberFormat getNumberFormat(BigDecimal value) {
		DecimalFormat formatter = (DecimalFormat) NumberFormat.getNumberInstance(this.locale);
		formatter.setMinimumFractionDigits(value.scale());

		return formatter;
	} // end getNumberFormat(BigDecimal)

} // end class FwImporter
