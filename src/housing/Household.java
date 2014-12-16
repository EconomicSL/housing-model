package housing;

import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import ec.util.MersenneTwisterFast;
/**********************************************
 * This represents a household who receives an income, consumes,
 * saves and can buy/sell/let/invest-in houses.
 * 
 * @author daniel, davidrpugh
 *
 **********************************************/
public class Household implements IHouseOwner {

	protected double annualEmploymentIncome;
	protected double bankBalance;

	/////////////////////////////////////////////////////////////////////////////////
	static public class Config {

		// ---- Parameters
		public ConsumptionEqn	consumptionEqn = new ConsumptionEqn();
		public PurchaseEqn		purchaseEqn = new PurchaseEqn();
		public SaleEqn			saleEqn = new SaleEqn();
		public Expectations expectedHousePrices = new ExtrapolativeExpectations(HousingMarketTest.rand);
		public double RENT_PROFIT_MARGIN = 0.0; // profit margin for buy-to-let investors
		public double HOUSE_SALE_PRICE_DISCOUNT = 0.95; // monthly discount on price of house for sale
		public double COST_OF_RENTING = 600; // Annual psychological cost of renting
		public double FTB_K = 1.0/100000.0;//0.005 // Heterogeneity of sensitivity of desire to first-time-buy to cost
		public double DOWNPAYMENT_FRACTION = 0.1 + 0.0025*HousingMarketTest.rand.nextGaussian(); // Fraction of bank-balance household would like to spend on mortgage downpayments
		
		public static double P_SELL = 1.0/(7.0*12.0); // monthly probability of selling house
		public static double INCOME_LOG_MEDIAN = Math.log(29580); // Source: IFS: living standards, poverty and inequality in the UK (22,938 after taxes) //Math.log(20300); // Source: O.N.S 2011/2012
		public static double INCOME_SHAPE = (Math.log(44360) - INCOME_LOG_MEDIAN)/0.6745; // Source: IFS: living standards, poverty and inequality in the UK (75th percentile is 32692 after tax)
		public static double RETURN_ON_FINANCIAL_WEALTH = 0.002; // monthly percentage growth of financial investements

		/////////////////////////////////////////////////////////////////////////////////
		static public class ConsumptionEqn {
			public double ALPHA = 0.2; // propensity to consume income
			public double BETA = 0.01; // propensity to consume liquid wealth
			
			public double desiredConsumption(double disposableIncome, double bankBalance) {
				if(disposableIncome > 0.0) {
					return(ALPHA*disposableIncome + BETA*bankBalance);
				} else {
					return(BETA*Math.max(bankBalance + disposableIncome,0.0));
				}
			}
			public double desiredConsumptionB(double monthlyIncome, double bankBalance) {
				return(0.1*Math.max((bankBalance - Math.exp(4.07*Math.log(monthlyIncome*12.0)-33.1 + 0.2*HousingMarketTest.rand.nextGaussian())),0.0));
			}
		}

		/////////////////////////////////////////////////////////////////////////////////
		static public class PurchaseEqn {
			static public double A = 0.2;//0.01;			// sensitivity to house price appreciation
			static public double EPSILON = 0.48;//0.365; // S.D. of noise
			static public double SIGMA = 5.0*12.0;//4.5*12.0;	// scale

			public double desiredPrice(double monthlyIncome, double expectedHousePrices) {
				return(SIGMA*monthlyIncome*Math.exp(EPSILON*HousingMarketTest.rand.nextGaussian())/(1.0 - A * expectedHousePrices));
			}
		}

		/////////////////////////////////////////////////////////////////////////////////
		static public class SaleEqn {
			static public double C = 0.095;	// initial markup from average price
			static public double D = 0.01;//0.001;		// Size of Days-on-market effect
			static public double E = 0.02; //0.05;	// SD of noise
			public double desiredPrice(double pbar, double d, double principal) {
				double exponent = C + Math.log(pbar) - D*Math.log((d + 1.0)/31.0) + E*HousingMarketTest.rand.nextGaussian();
				return(Math.max(Math.exp(exponent), principal));
			}

		}
		
		public PurchaseEqn getPurchaseEqn() {
			return(new PurchaseEqn());
		}

	}
		
	/********************************************************
	 * Constructor.
	 ********************************************************/
	public Household() {
		this(new Household.Config());
	}

	public Household(Household.Config c) {
		config = c;
		bank = HousingMarketTest.bank;
		houseMarket = HousingMarketTest.housingMarket;
		rentalMarket = HousingMarketTest.rentalMarket;
		rand = HousingMarketTest.rand;
		home = null;
		bankBalance = 0.0;
		isFirstTimeBuyer = true;
		id = ++id_pool;
	}

	/**
	 * @return total annual income tax due
	 */
	public double getAnnualIncomeTax() {
		return HousingMarketTest.government.incomeTaxDue(annualEmploymentIncome);
	}

	/**
	 * @return total annual national insurance contributions
	 */
	public double getAnnualNationalInsuranceTax() {
		return HousingMarketTest.government.class1NICsDue(annualEmploymentIncome);
	}

	/**
	 * @return total annual taxes due
	 */
	public double getAnnualTotalTax() {
		return getAnnualIncomeTax() + getAnnualNationalInsuranceTax();
	}

	/**
	 * @return expected house price appreciation (HPA).
	 */
	public double getExpectedHPA() {
		double currentHPA = houseMarket.HPIAppreciation;
		double expectedHPA = config.expectedHousePrices.formExpectation(currentHPA);
		config.expectedHousePrices.previousValue = currentHPA;
		//System.out.println(expectedHPA);
		//System.out.println(config.expectedHousePrices.speedOfAdjustment);
		return expectedHPA;
	}

	/**
	 * @return discretionary income is disposable income less any mortgage payments
	 */
	public double getMonthlyDiscretionaryIncome() {
		return getMonthlyDisposableIncome() - getMonthlyTotalMortgagePayments();
	}

	/**
	 * @return monthly disposable (i.e., after tax) income
	 */
	public double getMonthlyDisposableIncome() {
		return getMonthlyTotalIncome() - getMonthlyTotalTax();
	}

	/**
	 * @return gross monthly employment (i.e., before tax) income
	 */
	public double getMonthlyEmploymentIncome() {
		return annualEmploymentIncome / 12.0;
	}

	/**
	 * @return monthly interest income
	 */
	public double getMonthlyInterestIncome() {
		return bankBalance * Config.RETURN_ON_FINANCIAL_WEALTH;
	}

	/**
	 * @return gross property income will be zero for most households
	 */
	public double getMonthlyPropertyIncome() {
		double propertyIncome = 0.0;
		for (Map.Entry<House, MortgageApproval> payment : housePayments.entrySet()) {
			if (isCollectingRentFrom(payment.getKey())) {
				propertyIncome += payment.getValue().monthlyPayment * (1.0 + config.RENT_PROFIT_MARGIN);
			}
		}
		return propertyIncome;
	}

	/**
	 * @return gross monthly total income
	 */
	public double getMonthlyTotalIncome() {
		double monthlyTotalIncome = (getMonthlyEmploymentIncome() +
				getMonthlyPropertyIncome() + getMonthlyInterestIncome());
		return monthlyTotalIncome;
	}

	/**
	 * @return monthly total monthly interest payments for all houses owned
	 */
	public double getMonthlyTotalInterestPayments() {
		double totalInterestPayments = 0.0;
		double interestPayment;
		if (! isRenting()) {
			for (Map.Entry<House, MortgageApproval> payment : housePayments.entrySet()) {
				interestPayment = payment.getValue().principal * payment.getValue().monthlyInterestRate;
				totalInterestPayments += interestPayment;
			}
		}
		return totalInterestPayments;
	}

	/**
	 * @return monthly total monthly mortgage payments for all houses owned
	 */
	public double getMonthlyTotalMortgagePayments() {
		double totalMortgagePayments = 0.0;
		if (! isRenting()) {
			for (Map.Entry<House, MortgageApproval> payment : housePayments.entrySet()) {
				totalMortgagePayments += payment.getValue().monthlyPayment;
			}
		}
		return totalMortgagePayments;
	}

	/**
	 * @return monthly total monthly principal payments for all houses owned
	 */
	public double getMonthlyTotalPrincipalPayments() {
		double totalPrincipalPayments = 0.0;
		double interestPayment, mortgagePayment;
		if (! isRenting()) {
			for (Map.Entry<House, MortgageApproval> payment : housePayments.entrySet()) {
				mortgagePayment = payment.getValue().monthlyPayment;
				interestPayment = payment.getValue().principal * payment.getValue().monthlyInterestRate;
				totalPrincipalPayments += mortgagePayment - interestPayment;
			}
		}
		return totalPrincipalPayments;
	}

	/**
	 * @return total monthly taxes due
	 */
	public double getMonthlyTotalTax() {
		return getAnnualTotalTax() / 12.0;
	}

	/////////////////////////////////////////////////////////
	// House market behaviour
	/////////////////////////////////////////////////////////

	/********************************************************
	 * First step in a time-step:
	 * Receive income, pay rent/mortgage, make consumption decision
	 * and make decision to buy/sell house.
	 ********************************************************/
	public void preHouseSaleStep() {
		double disposableIncome;
		
		disposableIncome = getMonthlyDisposableIncome() - 0.8 * Government.Config.INCOME_SUPPORT;

//		System.out.println("income = "+monthlyIncome+" disposable = "+disposableIncome );
		
		// ---- Pay rent/mortgage(s)
		Iterator<Map.Entry<House,MortgageApproval> > mapIt = housePayments.entrySet().iterator();
		Map.Entry<House,MortgageApproval> payment;
		while(mapIt.hasNext()) {
			payment = mapIt.next();
			if(payment.getValue().nPayments > 0) {
				disposableIncome -= payment.getValue().makeMonthlyPayment();
				if(isCollectingRentFrom(payment.getKey())) {
					// profit from rent collection
					//disposableIncome += payment.getValue().monthlyPayment*(1.0+config.RENT_PROFIT_MARGIN);
				}
				if(payment.getValue().nPayments == 0) { // do paid-off stuff
					if(payment.getKey().owner != this) { // renting
						payment.getKey().owner.endOfLettingAgreement(payment.getKey());
						if(payment.getKey() == home) {
							home.resident = null;
							home = null;
						}
						mapIt.remove();
					}
				}
			}
		}
		
		// --- consume
//		bankBalance += disposableIncome - config.consumptionEqn.desiredConsumption(disposableIncome,bankBalance);
		bankBalance += disposableIncome - config.consumptionEqn.desiredConsumptionB(getMonthlyEmploymentIncome(),bankBalance);
//		bankBalance += -config.consumptionEqn.desiredConsumptionB(monthlyIncome,bankBalance);
		
		if(bankBalance < 0.0) {
			// bankrupt behaviour
			System.out.println("Household gone bankrupt!");
			System.out.println("...Houses = "+housePayments.size());
//			int i = 0;
//			for(House h : housePayments.keySet()) {
//				if(h.resident == null) ++i;
//			}
//			System.out.println("...Empty = "+i);
				
			// TODO: cash injection for now...
			bankBalance = 1.0;
		}
		
		makeHousingDecision();
	}

	/********************************************************
	 * Second step in a time-step. At this point, the
	 * household may have sold their house, but not managed
	 * to buy a new one, so must enter the rental market.
	 * 
	 * This is also where investors get to bid for buy-to-let
	 * housing.
	 ********************************************************/
	public void preHouseLettingStep() {
		if(isHomeless()) {
			rentalMarket.bid(this, desiredRent());
		} else if(housePayments.size() > 1) { // this is a buy-to-let investor
			houseMarket.bid(this, bank.getMaxMortgage(this, false));
		}
	}
	
	/********************************************************
	 *  Make decision to buy/sell houses
	 ********************************************************/
	void makeHousingDecision() {
		// --- add and manage houses for sale
		HouseSaleRecord forSale;
		double newPrice;
		
		for(House h : housePayments.keySet()) {
			if(h.owner == this) {
				forSale = houseMarket.getSaleRecord(h);
				if(forSale != null) { // reprice house for sale
					newPrice = rethinkHouseSalePrice(forSale);
					if(newPrice > housePayments.get(h).principal) {
						houseMarket.updateOffer(h, newPrice);						
					} else {
						houseMarket.removeOffer(h);
					}
				} else if(decideToSellHouse(h)) { // put house on market
					houseMarket.offer(h, config.saleEqn.desiredPrice(
							houseMarket.averageSalePrice[h.quality],
							houseMarket.averageDaysOnMarket,
							housePayments.get(h).principal
					));
				}
			}
		}
		
		// ---- try to buy house?
		if(!isHomeowner()) {
			decideToBuyFirstHome();
		}
	}
	
	/////////////////////////////////////////////////////////
	// Houseowner interface
	/////////////////////////////////////////////////////////

	/********************************************************
	 * Do all the stuff necessary when this household
	 * buys a house:
	 * Give notice to landlord if renting,
	 * Get loan from mortgage-lender,
	 * Pay for house,
	 * Put house on rental market if buy-to-let and no tenant.
	 ********************************************************/
	public void completeHousePurchase(HouseSaleRecord sale) {
		if(isRenting()) { // give immediate notice to landlord
			if(home != sale.house) home.owner.endOfLettingAgreement(home);
			housePayments.remove(home);
			home.resident = null;
			home = null;
		}
		MortgageApproval mortgage = bank.requestLoan(this, sale.currentPrice, bankBalance*config.DOWNPAYMENT_FRACTION, home == null);
		if(mortgage == null) {
			// TODO: throw exception
			System.out.println("Can't afford to buy house: strange");
			System.out.println("Want "+sale.currentPrice+" but can only get "+bank.getMaxMortgage(this,home==null));
			System.out.println("Bank balance is "+bankBalance+". DisposableIncome is "+ getMonthlyDiscretionaryIncome());
			System.out.println("Annual income is "+ getMonthlyEmploymentIncome() *12.0);
			if(isRenting()) System.out.println("Is renting");
			if(isHomeowner()) System.out.println("Is homeowner");
			if(isHomeless()) System.out.println("Is homeless");
			if(isFirstTimeBuyer()) System.out.println("Is firsttimebuyer");
		}
		bankBalance -= mortgage.downPayment;
		housePayments.put(sale.house, mortgage);
		if(home == null) {
			home = sale.house;
			sale.house.resident = this;
		} else if(sale.house.resident == null) { // put empty buy-to-let house on rental market
			endOfLettingAgreement(sale.house);
		}
		isFirstTimeBuyer = false;
	}
		
	/********************************************************
	 * Do all stuff necessary when this household sells a house
	 ********************************************************/
	public void completeHouseSale(HouseSaleRecord sale) {
		double profit = sale.currentPrice - housePayments.get(sale.house).payoff(bankBalance+sale.currentPrice);
		if(profit < 0) System.out.println("Strange: Profit is negative.");
		bankBalance += profit;
		if(housePayments.get(sale.house).nPayments == 0) {
			housePayments.remove(sale.house);
		}
		if(sale.house == home) {
			home.resident = null;
			home = null;
			bidOnHousingMarket(1.0);
		}
	}
	
	/********************************************************
	 * A household receives this message when a tenant moves
	 * out of one of its buy-to-let houses.
	 * 
	 * The household simply puts the house back on the rental
	 * market.
	 ********************************************************/
	@Override
	public void endOfLettingAgreement(House h) {
		// put house back on rental market
		if(!housePayments.containsKey(h)) {
			System.out.println("I don't own this house: strange");
		}
		rentalMarket.offer(h, housePayments.get(h).monthlyPayment*(1.0+config.RENT_PROFIT_MARGIN));
	}


	/********************************************************
	 * Do all the stuff necessary when this household moves
	 * in to rented accommodation (i.e. set up a regular
	 * payment contract. At present we use a MortgageApproval).
	 ********************************************************/
	public void completeHouseRental(HouseSaleRecord sale) {
		MortgageApproval rent = new MortgageApproval();
		rent.downPayment = 0.0;
		rent.monthlyPayment = sale.currentPrice;
		rent.monthlyInterestRate = 0.0;
		rent.nPayments = (int)(12.0*rand.nextDouble()+1);
		rent.principal = rent.monthlyPayment*rent.nPayments;
		home = sale.house;
		sale.house.resident = this;
		housePayments.put(home, rent);
	}


	/////////////////////////////////////////////////////////
	// Homeowner helper stuff
	/////////////////////////////////////////////////////////

	/****************************************
	 * Put a bid on the housing market if this household can afford a
	 * mortgage at its desired price.
	 * 
	 * @param p The probability that the household will actually bid,
	 * given that it can afford a mortgage.
	 ****************************************/
	protected void bidOnHousingMarket(double p) {
		double desiredPrice = config.purchaseEqn.desiredPrice(getMonthlyEmploymentIncome(), getExpectedHPA());
		double maxMortgage = bank.getMaxMortgage(this, true);
		if(desiredPrice <= maxMortgage) {
			if(p<1.0) {
				if(rand.nextDouble() < p) houseMarket.bid(this, desiredPrice);
			} else {
				// no need to call random if p = 1.0
				houseMarket.bid(this, desiredPrice);				
			}
		}
	}
	
	/********************************************************
	 * Make the decision whether to bid on the housing market when renting.
	 * This is an "intensity of choice" decision (sigma function)
	 * on the cost of renting compared to the cost of owning, with
	 * COST_OF_RENTING being an intrinsic psychological cost of not
	 * owning. 
	 ********************************************************/
	protected void decideToBuyFirstHome() {
		double costOfHouse;
		double costOfRent;
		double p = config.purchaseEqn.desiredPrice(getMonthlyEmploymentIncome(), getExpectedHPA());
		double maxMortgage = bank.getMaxMortgage(this, true);
		if(p <= maxMortgage) {
			costOfHouse = p*((1.0-HousingMarketTest.bank.config.THETA_FTB)*bank.mortgageInterestRate() - 12.0*houseMarket.housePriceAppreciation());
			if(home != null) {
				costOfRent = housePayments.get(home).monthlyPayment*12;
			} else {
				costOfRent = rentalMarket.averageSalePrice[0]*12;
			}
			if(rand.nextDouble() < 1.0/(1.0 + Math.exp(-config.FTB_K*(costOfRent + config.COST_OF_RENTING - costOfHouse)))) {
				houseMarket.bid(this, p);
			}
		}
	}
	
	/********************************************************
	 * Calculate the price of a house that this household would like to buy
	 * 
	 * @return The desired price.
	 ********************************************************/
//	public double desiredHousePurchasePrice() {
//		final double h = 0.4;//38.8;
//		final double g = 1.0;//0.56;
//		final double a = 0.01;//0.16;//0.16;
//		final double tau = 0.02;
//		final double c = 0.03;
//		double epsilon;
		
//		epsilon = Math.exp(0.46*rand.nextGaussian() - 0.13);

		//		return(epsilon * h * Math.pow(monthlyPersonalIncome*12, g)/
//		(tau + c + bank.loanToValue(this,true)*bank.mortgageInterestRate() - a*houseMarket.housePriceAppreciation()));
		
//		return(config.purchaseEqn.SIGMA*monthlyPersonalIncome*12.0*Math.exp(config.purchaseEqn.EPSILON*rand.nextGaussian())/
//				(1.0 - config.purchaseEqn.A*houseMarket.housePriceAppreciation()));
//	}
	
	/********************************************************
	 * Decide whether to sell ones own house.
	 ********************************************************/
	private boolean decideToSellHouse(House h) {
		if(rand.nextDouble() < Config.P_SELL) return(true);
		return false;
	}

	/********************************************************
	 * Decide the initial list price if this household was to put
	 * its own home on the market.
	 ********************************************************/
//	public double desiredHouseSalePrice(House house) {
		/**	Original version (Axtell):	
		double exponent = 
				0.22
				+ 0.99*Math.log(houseMarket.averageListPrice[house.quality])
				+ 0.22*Math.log(houseMarket.averageSoldPriceToOLP)
				- 0.01*Math.log(houseMarket.averageDaysOnMarket + 1)
				+ 0.01*rand.nextGaussian();
				**/
//		double exponent = 
//				0.095
//				+ Math.log(houseMarket.averageSalePrice[house.quality])
//				- 0.01*Math.log((houseMarket.averageDaysOnMarket + 1.0)/31.0)
//				+ 0.05*rand.nextGaussian();
//		return(Math.max(Math.exp(exponent), housePayments.get(house).principal));
//	}

	
	/********************************************************
	 * Decide how much to drop the list-price of a house if
	 * it has been on the market for (another) month and hasn't
	 * sold.
	 * 
	 * @param sale The HouseSaleRecord of the house that is on the market.
	 ********************************************************/
	protected double rethinkHouseSalePrice(HouseSaleRecord sale) {
		return(sale.currentPrice * config.HOUSE_SALE_PRICE_DISCOUNT);
	}

	
	/********************************************************
	 * Decide how much to bid on the rental market
	 ********************************************************/
	public double desiredRent() {
		return(0.3* getMonthlyEmploymentIncome());
	}
	
	/********************************************************
	 * Decide whether to buy a house as a buy-to-let investment
	 ********************************************************/
	public boolean decideToBuyBuyToLet(House h, double price) {
		if(price <= bank.getMaxMortgage(this, false)) {
			MortgageApproval mortgage;
			double yield;
			mortgage = bank.requestLoan(this, price, bankBalance * config.DOWNPAYMENT_FRACTION, false);
			
			yield = (mortgage.monthlyPayment*12*config.RENT_PROFIT_MARGIN + houseMarket.housePriceAppreciation()*price)/
					mortgage.downPayment;
			
			if(rand.nextDouble() < 1.0/(1.0 + Math.exp(4.5 - yield*24.0))) {
				return(true);
			}
		}
		return(false);
	}
	
	/////////////////////////////////////////////////////////
	// Helpers
	/////////////////////////////////////////////////////////


	public boolean isHomeowner() {
		if(home == null) return(false);
		return(home.owner == this);
	}

	public boolean isRenting() {
		if(home == null) return(false);
		return(home.owner != this);
	}

	public boolean isHomeless() {
		return(home == null);
	}

	public boolean isFirstTimeBuyer() {
		return isFirstTimeBuyer;
	}

	public boolean isPropertyInvestor() {
		return(housePayments.size() > 1);
	}
	
	public boolean isCollectingRentFrom(House h) {
		return(h.owner == this && h != home && h.resident != null);
	}

	///////////////////////////////////////////////
	
	Household.Config	config;
	HouseSaleMarket		houseMarket;
	HouseRentalMarket	rentalMarket;

	protected House		home; // current home
	protected Map<House, MortgageApproval> 		housePayments = new TreeMap<House, MortgageApproval>(); // houses owned
	private boolean		isFirstTimeBuyer;
	Bank				bank;
	//double				age;
	protected MersenneTwisterFast 	rand;
	public int		 id;
	static int		 id_pool;

	
	// ---- Parameters
	/**
	public double ALPHA = 0.2; // propensity to consume income
	public double BETA = 0.01; // propensity to consume liquid wealth
	public double RENT_PROFIT_MARGIN = 0.0; // profit margin for buy-to-let investors
	public double P_SELL = 1.0/(7.0*12.0); // monthly probability of selling house
	public double HOUSE_SALE_PRICE_DISCOUNT = 0.95; // monthly discount on price of house for sale
	public double INCOME_LOG_MEDIAN = Math.log(29580); // Source: IFS: living standards, poverty and inequality in the UK (22,938 after taxes) //Math.log(20300); // Source: O.N.S 2011/2012
	public double INCOME_SHAPE = (Math.log(44360) - INCOME_LOG_MEDIAN)/0.6745; // Source: IFS: living standards, poverty and inequality in the UK (75th percentile is 32692 after tax)
	public double COST_OF_RENTING = 600; // Annual psychological cost of renting
	public double FTB_K = 0.005; // Heterogeneity of sensitivity of desire to first-time-buy to cost
	**/
//	protected static final double INCOME_LOG_95_PERCENTILE = Math.log(66200); // One-tailed percentile. Source: O.N.S. 2011/2012
//	protected static final double INCOME_SHAPE = (INCOME_LOG_95_PERCENTILE-INCOME_LOG_MEDIAN)/1.64; // Shape parameter of lognormal distribution
	
	
}
