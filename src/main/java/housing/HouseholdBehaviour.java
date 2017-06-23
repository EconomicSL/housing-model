package housing;

import java.io.Serializable;

import configuration.HouseholdBehaviorConfig;
import org.apache.commons.math3.distribution.LogNormalDistribution;

//import ec.util.MersenneTwisterFast;

/**
 * This class implements the behavioural decisions made by households
 *
 * @author daniel
 */
public class HouseholdBehaviour implements Serializable {// implements IHouseholdBehaviour {
    private static final long serialVersionUID = -7785886649432814279L;

    private HouseholdBehaviorConfig config;
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//    public final double DOWNPAYMENT_FRACTION = 0.75 + 0.0025*rand.nextGaussian(); // Fraction of bank-balance household would like to spend on mortgage downpayments
//    public final double INTENSITY_OF_CHOICE = 10.0;

    private Model.MersenneTwister    rand = Model.rand;    // Passes the Model's random number generator to a private field
    public boolean                    BTLInvestor;
    public double                     propensityToSave;
    public double                    desiredBalance;
    public double                     BtLCapGainCoeff; // Sensitivity of BtL investors to capital gain, 0.0 cares only about rental yield, 1.0 cares only about cap gain

    // Size distributions for downpayments of first-time-buyers and owner-occupiers
    public LogNormalDistribution FTB_DOWNPAYMENT = new LogNormalDistribution(rand, config.DOWNPAYMENT_FTB_SCALE,
            config.DOWNPAYMENT_FTB_SHAPE);
    public LogNormalDistribution OO_DOWNPAYMENT = new LogNormalDistribution(rand, config.DOWNPAYMENT_OO_SCALE,
            config.DOWNPAYMENT_OO_SHAPE);

    public double sigma(double x) { // the Logistic function, sometimes called sigma function, 1/1+e^(-x)
        return 1.0/(1.0+Math.exp(-1.0*x));
    }

    /***************************************
     * Constructor: initialise the behavioural variables for a new household: propensity to save, and
     * if the income percentile is above a minimum, decide whether to give the household
     * the BTL investor 'gene', and if so, decide whether they will be a fundamentalist or trend follower investor
     *
     * @param incomePercentile the fixed income percentile for the household (assumed constant over a lifetime),
     *                         used to determine whether the household can be a BTL investor
     ***************************************************/
    public HouseholdBehaviour(HouseholdBehaviorConfig config, double incomePercentile) {
        this.config = config;

        // Propensity to save is computed here so that it is constant for a given agent
        propensityToSave = config.getDesiredBankBalanceConfig().getEpsilon()*rand.nextGaussian();
        BtLCapGainCoeff = 0.0;
        if(config.BTL_ENABLED) {
            if(incomePercentile > config.getBuyToLetConfig().getMinIncomePercentile() &&
                    rand.nextDouble() < config.getBuyToLetConfig().getProbabilityInvestor() / config.getBuyToLetConfig().getMinIncomePercentile()) {
                BTLInvestor = true;//(data.Households.buyToLetDistribution.inverseCumulativeProbability(rand.nextDouble())+0.5);
                double type = rand.nextDouble();
                if(type < config.getBuyToLetConfig().getProbabilityFundamentalist()) {
                    BtLCapGainCoeff = config.getBuyToLetConfig().getFundamentalistCapitalGainCoefficient();
                } else {
                    BtLCapGainCoeff = config.getBuyToLetConfig().getTrendFollowersCapitalGainCoefficient();
                }
            } else {
                BTLInvestor = false;
            }
        } else {
            BTLInvestor = false;
        }
        desiredBalance = -1.0;
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Owner-Ocupier behaviour
    ///////////////////////////////////////////////////////////////////////////////////////////////

    /********************************
     * How much a household consumes (optional, non-essential consumption)
     * Consumption rule made to fit ONS wealth in Great Britain data.
     *
     * @param me Household
     * @return Non-essential consumption for the month
     ********************************/
    public double desiredConsumptionB(Household me) {//double monthlyIncome, double bankBalance) {
        return(config.getConsumptionDecisionRuleConfig().getConsumptionFraction()*Math.max(me.getBankBalance() - desiredBankBalance(me), 0.0));
    }

    /********************************
     * @param me Household
     * @return Minimum bank balance a household would like to have at the end of the month (like a minimum safety net).
     * Used to determine non-essential consumption.
     *********************************/
    public double desiredBankBalance(Household me) {
        // TODO: why only if desired bank balance is set to -1? (does this get calculated only once? why?)
        if(desiredBalance == -1.0) {
//            desiredBalance = 3.0*Math.exp(4.07*Math.log(me.getMonthlyPreTaxIncome()*12.0)-33.1 - propensityToSave);
            double lnDesiredBalance = config.getDesiredBankBalanceConfig().getAlpha()
                    + config.getDesiredBankBalanceConfig().getBeta()
                        * Math.log(me.getMonthlyPreTaxIncome()*config.constants.MONTHS_IN_YEAR) + propensityToSave;
            desiredBalance = Math.exp(lnDesiredBalance);
            // TODO: What is this next rule? Not declared in the article! Check if 0.3 should be included as a parameter
            if(me.lifecycle.incomePercentile < 0.3 && !isPropertyInvestor()) desiredBalance = 1.0;
        }
        return(desiredBalance);
    }

    /***************************
     * @param me Household
     * @param monthlyIncome Mohthly income
     * @return desired purchase price after having decided to buy a house
     ****************************/
    public double desiredPurchasePrice(Household me, double monthlyIncome) {
        return(config.BUY_SCALE*config.constants.MONTHS_IN_YEAR*monthlyIncome
                *Math.exp(config.BUY_EPSILON*rand.nextGaussian())
                /(1.0 - config.BUY_WEIGHT_HPA*HPAExpectation()));
        
//        PurchasePlan plan = findBestPurchase(me);
//        double housePrice = Model.housingMarket.getAverageSalePrice(plan.quality);//behaviour.desiredPurchasePrice(getMonthlyPreTaxIncome(), houseMarket.housePriceAppreciation());
//        return(1.01*housePrice*Math.exp(0.05*rand.nextGaussian()));
    }

    /********************************
     * @param pbar average sale price of houses of the same quality
     * @param d average number of days on the market before sale // TODO: Is this average or for this property
     * @param principal amount of principal left on any mortgage on this house
     * @return initial sale price of a house 
     ********************************/
    public double initialSalePrice(double pbar, double d, double principal) {
        // TODO: During the first month, the third term is actually introducing an extra markup. Solve!
        double exponent = config.SALE_MARKUP + Math.log(pbar)
                - config.SALE_WEIGHT_DAYS_ON_MARKET*Math.log((d + 1.0)/(config.constants.DAYS_IN_MONTH + 1.0))
                + config.SALE_EPSILON*rand.nextGaussian();
        return(Math.max(Math.exp(exponent), principal));
    }
    

    /**
     * @return Does an owner-occupier decide to sell house?
     */
    public boolean decideToSellHome(Household me) {
        // TODO: need to add expenditure
        if(isPropertyInvestor()) return(false);
        return(rand.nextDouble() < config.derivedParams.MONTHLY_P_SELL*(1.0
                + config.DECISION_TO_SELL_ALPHA*(config.DECISION_TO_SELL_HPC
                - Model.housingMarket.offersPQ.size()*1.0/Model.households.size()))
                + config.DECISION_TO_SELL_BETA*(config.DECISION_TO_SELL_INTEREST - Model.bank.getMortgageInterestRate()));

        // reference 
        //int potentialQualityChange = Model.housingMarket.maxQualityGivenPrice(Model.bank.getMaxMortgage(me,true))- me.home.getQuality();
        //double p_move = data.Households.P_FORCEDTOMOVE + (data.Households.P_SELL-data.Households.P_FORCEDTOMOVE)/(1.0+Math.exp(5.0-2.0*potentialQualityChange));
        
        /*
        
        // calc purchase price
        PurchasePlan plan = findBestPurchase(me);
        if(plan.quality < 0) return(false); // can't afford new home anyway
        int currentQuality = me.home.getQuality();
        double currentUtility;// = utilityOfHome(me,me.home.getQuality()) - me.mortgageFor(me.home).nextPayment()/me.getMonthlyPreTaxIncome();
//        currentUtility = utilityOfHome(me,currentQuality) +(Model.housingMarket.getAverageSalePrice(currentQuality)*HPAExpectation()/12.0 - me.mortgageFor(me.home).nextPayment())/me.getMonthlyPreTaxIncome();
        double currentLeftForConsumption = 1.0 - (me.mortgageFor(me.home).nextPayment() - Model.housingMarket.getAverageSalePrice(currentQuality)*HPAExpectation()/12.0)/me.monthlyEmploymentIncome;
//        currentUtility = (currentQuality-me.desiredQuality)/House.Config.N_QUALITY + qualityOfLiving(currentLeftForConsumption);
        currentUtility = utilityOfHome(me, currentQuality) + qualityOfLiving(currentLeftForConsumption);
//    System.out.println("Move utility = "+(plan.utility- currentUtility));

        double p_move = data.Households.P_FORCEDTOMOVE;
        p_move += 2.0*(data.Households.P_SELL-data.Households.P_FORCEDTOMOVE)/(1.0+Math.exp(4.0-INTENSITY_OF_CHOICE*(plan.utility - currentUtility)));
        p_move *= 1.0 - data.HouseSaleMarket.SEASONAL_VOL_ADJ*Math.cos((2.0*3.141/12.0)*Model.getMonth());
    //    System.out.println("Move utility = "+INTENSITY_OF_CHOICE*(plan.utility- currentUtility)+"  "+p_move);
        return(rand.nextDouble() < p_move);
        */
    }

    /**
     *
     * @param me the household
     * @param housePrice the price of the house
     * @return the downpayment
     */
    public double downPayment(Household me, double housePrice) {
//        return(me.getBankBalance() - (1.0 - DOWNPAYMENT_FRACTION)*desiredBankBalance(me));
        if(me.getBankBalance() > housePrice*config.BANK_BALANCE_FOR_CASH_DOWNPAYMENT) { // calibrated against mortgage approval/housing transaction ratio, core indicators average 1987-2006
            return(housePrice);
        }
        double downpayment;
        if(me.isFirstTimeBuyer()) {
            downpayment = Model.housingMarket.housePriceIndex*FTB_DOWNPAYMENT.inverseCumulativeProbability(Math.max(0.0,
                    (me.lifecycle.incomePercentile - config.DOWNPAYMENT_MIN_INCOME)/(1 - config.DOWNPAYMENT_MIN_INCOME)));
        } else if(isPropertyInvestor()) {
            downpayment = housePrice*(Math.max(0.0,
                    config.DOWNPAYMENT_BTL_MEAN + config.DOWNPAYMENT_BTL_EPSILON*rand.nextGaussian())); // calibrated...
            //downpayment = housePrice*(Math.max(0.0, 0.26+0.08*rand.nextGaussian())); // calibrated...
        } else {
            downpayment = Model.housingMarket.housePriceIndex*OO_DOWNPAYMENT.inverseCumulativeProbability(Math.max(0.0,
                    (me.lifecycle.incomePercentile - config.DOWNPAYMENT_MIN_INCOME)/(1 - config.DOWNPAYMENT_MIN_INCOME)));
        }
        if(downpayment > me.getBankBalance()) downpayment = me.getBankBalance();
        return(downpayment);
//        return(Model.housingMarket.housePriceIndex*OO_DOWNPAYMENT.inverseCumulativeProbability(me.lifecycle.incomePercentile));    
    }

    
    /********************************************************
     * Decide how much to drop the list-price of a house if
     * it has been on the market for (another) month and hasn't
     * sold. Calibrated against Zoopla dataset in Bank of England
     * 
     * @param sale The HouseSaleRecord of the house that is on the market.
     ********************************************************/
    public double rethinkHouseSalePrice(HouseSaleRecord sale) {
//        return(sale.getPrice() *0.95);

        if(rand.nextDouble() < config.P_SALE_PRICE_REDUCE) {
            double logReduction = config.REDUCTION_MU+(rand.nextGaussian()*config.REDUCTION_SIGMA);
//            System.out.println(1.0-Math.exp(logReduction)/100.0);
            return(sale.getPrice() * (1.0-Math.exp(logReduction)/100.0));
//            return(sale.getPrice() * (1.0-data.Households.REDUCTION_MU/100.0) + rand.nextGaussian()*data.Households.REDUCTION_SIGMA/100.0);
        }
        return(sale.getPrice());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Renter behaviour
    ///////////////////////////////////////////////////////////////////////////////////////////////
    
    /*** renters or OO after selling home decide whether to rent or buy
     * N.B. even though the HH may not decide to rent a house of the
     * same quality as they would buy, the cash value of the difference in quality
     *  is assumed to be the difference in rental price between the two qualities.
     *  @return true if we should buy a house, false if we should rent
     */
    public boolean rentOrPurchaseDecision(Household me) {
        if(isPropertyInvestor()) return(true);

        double purchasePrice = Math.min(desiredPurchasePrice(me, me.monthlyEmploymentIncome),
                Model.bank.getMaxMortgage(me, true));
        MortgageAgreement mortgageApproval = Model.bank.requestApproval(me, purchasePrice,
                downPayment(me,purchasePrice), true);
        int newHouseQuality = Model.housingMarket.maxQualityGivenPrice(purchasePrice);
//        int rentalQuality = Model.rentalMarket.maxQualityGivenPrice(desiredRent(me, me.monthlyEmploymentIncome));
//        if(rentalQuality > newHouseQuality+House.Config.N_QUALITY/8) return(false); // better quality to rent
        if(newHouseQuality < 0) return(false); // can't afford a house anyway
        double costOfHouse = mortgageApproval.monthlyPayment*config.constants.MONTHS_IN_YEAR - purchasePrice*HPAExpectation();
        double costOfRent = Model.rentalMarket.getAverageSalePrice(newHouseQuality)*config.constants.MONTHS_IN_YEAR;
//        System.out.println(FTB_K*(costOfRent + COST_OF_RENTING - costOfHouse));
        //return(rand.nextDouble() < 1.0/(1.0 + Math.exp(-FTB_K*(costOfRent*(1.0+COST_OF_RENTING) - costOfHouse))));
        return(rand.nextDouble() < sigma(config.SENSITIVITY_RENT_OR_PURCHASE*(costOfRent*(1.0
                + config.PSYCHOLOGICAL_COST_OF_RENTING) - costOfHouse)));

        /*
        
        PurchasePlan purchase = findBestPurchase(me);
        if(purchase.quality == 0 && purchase.utility <-10.0) return(false); // can't afford to buy anyway
        int rentQuality = findBestRentalQuality(me);
        
        if(me.isFirstTimeBuyer()) {
            COST_OF_RENTING = 0.001;
        } else {
            COST_OF_RENTING = 0.01;
        }
        
        double pBuy = 1.0/(1.0 + Math.exp(-INTENSITY_OF_CHOICE*(COST_OF_RENTING + purchase.utility - utilityOfRenting(me, rentQuality))));
//        System.out.println(utilityOfRenting(me, rentQuality) + " : "+purchase.utility+" : "+INTENSITY_OF_CHOICE*(COST_OF_RENTING+purchase.utility-utilityOfRenting(me, rentQuality))+" ... "+pBuy);
        return(rand.nextDouble() < pBuy);
                 */

    }

    public boolean rentOrPurchaseDecision(Household me, double desiredPurchasePrice) {
        if(isPropertyInvestor()) return(true);

        double purchasePrice = Math.min(desiredPurchasePrice, Model.bank.getMaxMortgage(me, true));
        MortgageAgreement mortgageApproval = Model.bank.requestApproval(me, purchasePrice,
                downPayment(me,purchasePrice), true);
        int newHouseQuality = Model.housingMarket.maxQualityGivenPrice(purchasePrice);
//        int rentalQuality = Model.rentalMarket.maxQualityGivenPrice(desiredRent(me, me.monthlyEmploymentIncome));
//        if(rentalQuality > newHouseQuality+House.Config.N_QUALITY/8) return(false); // better quality to rent
        if(newHouseQuality < 0) return(false); // can't afford a house anyway
        double costOfHouse = mortgageApproval.monthlyPayment*config.constants.MONTHS_IN_YEAR - purchasePrice*HPAExpectation();
        double costOfRent = Model.rentalMarket.getAverageSalePrice(newHouseQuality)*config.constants.MONTHS_IN_YEAR;
//        System.out.println(FTB_K*(costOfRent + COST_OF_RENTING - costOfHouse));
        //return(rand.nextDouble() < 1.0/(1.0 + Math.exp(-FTB_K*(costOfRent*(1.0+COST_OF_RENTING) - costOfHouse))));
        return(rand.nextDouble() < sigma(config.SENSITIVITY_RENT_OR_PURCHASE*(costOfRent*(1.0
                + config.PSYCHOLOGICAL_COST_OF_RENTING) - costOfHouse)));

        /*

        PurchasePlan purchase = findBestPurchase(me);
        if(purchase.quality == 0 && purchase.utility <-10.0) return(false); // can't afford to buy anyway
        int rentQuality = findBestRentalQuality(me);

        if(me.isFirstTimeBuyer()) {
            COST_OF_RENTING = 0.001;
        } else {
            COST_OF_RENTING = 0.01;
        }

        double pBuy = 1.0/(1.0 + Math.exp(-INTENSITY_OF_CHOICE*(COST_OF_RENTING + purchase.utility - utilityOfRenting(me, rentQuality))));
//        System.out.println(utilityOfRenting(me, rentQuality) + " : "+purchase.utility+" : "+INTENSITY_OF_CHOICE*(COST_OF_RENTING+purchase.utility-utilityOfRenting(me, rentQuality))+" ... "+pBuy);
        return(rand.nextDouble() < pBuy);
                 */

    }

    /********************************************************
     * Decide how much to bid on the rental market
     * Source: Zoopla rental prices 2008-2009 (at Bank of England)
     ********************************************************/
    public double desiredRent(Household me, double monthlyIncome) {
        return(monthlyIncome * config.DESIRED_RENT_INCOME_FRACTION);

//        int quality = findBestRentalQuality(me);
//        return(1.01*Model.rentalMarket.getAverageSalePrice(quality)*Math.exp(0.1*rand.nextGaussian()));
        
        /*
        // Zoopla calibrated values
        double annualIncome = monthlyIncome*12.0; // this should be net annual income, not gross
        double rent;
        if(annualIncome < 12000.0) {
            rent = 386.0;
        } else {
            rent = 11.72*Math.pow(annualIncome, 0.372);
        }
        rent *= Math.exp(rand.nextGaussian()*0.0826);
        return(rent);
        */
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Property investor behaviour
    ///////////////////////////////////////////////////////////////////////////////////////////////
    
    /**
     * Decide whether to sell an investment property.
     * 
     * @param h The house in question
     * @param me The investor
     * @return Does an investor decide to sell a buy-to-let property (per month)
     */
    public boolean decideToSellInvestmentProperty(House h, Household me) {
        if(me.nInvestmentProperties() < 2) return(false); // Always keep at least one property

        double effectiveYield;
        
        // sell if not selling on rental market at interest coverage ratio of 1.0 (?)
        if(!h.isOnRentalMarket()) return(false); // don't sell while occupied by tenant
        MortgageAgreement mortgage = me.mortgageFor(h);
        //if(mortgage == null) {
        if(h.owner!=me){
            System.out.println("Strange: deciding to sell investment property that I don't own");
            return(false);
        }
        // TODO: add transaction costs to expected capital gain
//        double icr = (h.rentalRecord.getPrice()-mortgage.nextPayment())/h.rentalRecord.getPrice();
        double marketPrice = Model.housingMarket.getAverageSalePrice(h.getQuality());
        // TODO: Why to call this "equity"? It is called "downpayment" in the article!
        double equity = Math.max(0.01, marketPrice - mortgage.principal);   // Dummy security parameter to avoid dividing by zero
        double leverage = marketPrice/equity;
        double rentalYield = h.rentalRecord.getPrice()*config.constants.MONTHS_IN_YEAR/marketPrice;
        double mortgageRate = mortgage.nextPayment()*config.constants.MONTHS_IN_YEAR/equity;
        if(config.BTL_YIELD_SCALING) {
            effectiveYield = leverage*((1.0 - BtLCapGainCoeff)*rentalYield
                    + BtLCapGainCoeff*(Model.rentalMarket.longTermAverageGrossYield + HPAExpectation())) - mortgageRate;
        } else {
            effectiveYield = leverage*(rentalYield + BtLCapGainCoeff*HPAExpectation()) - mortgageRate;
        }
        double pKeep = Math.pow(sigma(config.BTL_CHOICE_INTENSITY*effectiveYield),
                1.0/config.constants.MONTHS_IN_YEAR);
        return(rand.nextDouble() < (1.0 - pKeep));
    }
    

    /**
     * How much rent does an investor decide to charge on a buy-to-let house? 
     * @param rbar average rent for house of this quality
     * @param d average days on market
     * @param h house being offered for rent
     */
    public double buyToLetRent(double rbar, double d, House h) {
        // TODO: What? Where does this equation come from?
        final double beta = config.RENT_MARKUP/Math.log(config.RENT_EQ_MONTHS_ON_MARKET); // Weight of days-on-market effect

        double exponent = config.RENT_MARKUP + Math.log(rbar)
                - beta*Math.log((d + 1.0)/(config.constants.DAYS_IN_MONTH + 1))
                + config.RENT_EPSILON*rand.nextGaussian();
        double result = Math.exp(exponent);
        // TODO: The following contains a fudge (config.RENT_MAX_AMORTIZATION_PERIOD) to keep rental yield up
        double minAcceptable = Model.housingMarket.getAverageSalePrice(h.getQuality())
                /(config.RENT_MAX_AMORTIZATION_PERIOD*config.constants.MONTHS_IN_YEAR);
        if(result < minAcceptable) result = minAcceptable;
        return(result);

    }

    /**
     * Update the demanded rent for a property
     *
     * @param sale the HouseSaleRecord of the property for rent
     * @return the new rent
     */
    public double rethinkBuyToLetRent(HouseSaleRecord sale) {
        return((1.0 - config.RENT_REDUCTION)*sale.getPrice());
//        if(rand.nextDouble() > 0.944) {
//            double logReduction = Math.min(4.6, 1.603+(rand.nextGaussian()*0.6173));
//            return(sale.getPrice() * (1.0-0.01*Math.exp(logReduction)));
//        }
//        return(sale.getPrice());
    }

    /***
     * Monthly opportunity of buying a new BTL property.
     *
     * Investor households with no investment properties always attempt to buy one. Households with at least
     * one investment property will calculate the expected yield of a new property based on two contributions:
     * capital gain and rental yield (with their corresponding weights which depend on the type of investor).
     *
     * @param me household
     * @return true if decision to buy
     */
    public boolean decideToBuyBuyToLet(Household me) {
        if(me.nInvestmentProperties() < 1) { // If I don't have any BTL properties, I always decide to buy one!!
            return(true);
        }
        double effectiveYield;
        
        if(!isPropertyInvestor()) return false;
        // TODO: This mechanism and its parameter are not declared in the article! Any reference for the value of the parameter?
        if(me.getBankBalance() < desiredBankBalance(me)*config.BTL_CHOICE_MIN_BANK_BALANCE) {
            return(false);
        }
        // --- calculate expected yield on zero quality house
        double maxPrice = Model.bank.getMaxMortgage(me, false);
        if(maxPrice < Model.housingMarket.getAverageSalePrice(0)) return false;
        
        MortgageAgreement m = Model.bank.requestApproval(me, maxPrice, 0.0, false); // maximise leverage with min downpayment
        
        double leverage = m.purchasePrice/m.downPayment;
        double rentalYield = Model.rentalMarket.averageSoldGrossYield;
        double mortgageRate = m.monthlyPayment*config.constants.MONTHS_IN_YEAR/m.downPayment;
        if(config.BTL_YIELD_SCALING) {
            effectiveYield = leverage*((1.0 - BtLCapGainCoeff)*rentalYield
                    + BtLCapGainCoeff*(Model.rentalMarket.longTermAverageGrossYield + HPAExpectation())) - mortgageRate;
        } else {
            effectiveYield = leverage*(rentalYield + BtLCapGainCoeff*HPAExpectation()) - mortgageRate;
        }
        //double pDontBuy = Math.pow(1.0/(1.0 + Math.exp(INTENSITY*effectiveYield)),AGGREGATE_RATE);
        //return(rand.nextDouble() < (1.0-pDontBuy));
        return (rand.nextDouble() < Math.pow(sigma(config.BTL_CHOICE_INTENSITY*effectiveYield),
                1.0/config.constants.MONTHS_IN_YEAR));
    }
    
    public double btlPurchaseBid(Household me) {
        return(Math.min(Model.bank.getMaxMortgage(me, false),
                1.1*Model.housingMarket.getAverageSalePrice(config.N_QUALITY-1)));
    }

    public boolean isPropertyInvestor() {
        return(BTLInvestor);
    }

    public boolean setPropertyInvestor(boolean isInvestor) {
        return(BTLInvestor = isInvestor);
    }

//    public int nDesiredBTLProperties() {
//        return desiredBTLProperties;
//    }

    /*** @returns expectation value of HPI in one year's time divided by today's HPI*/
    public double HPAExpectation() {
        // Dampening or multiplier factor, depending on its value being <1 or >1, for the current trend of HPA when
        // computing expectations as in HPI(t+DT) = HPI(t) + FACTOR*DT*dHPI/dt (double)
        return(Model.housingMarket.housePriceAppreciation(config.HPA_YEARS_TO_CHECK)*config.HPA_EXPECTATION_FACTOR);
    }
    
    /*
    public double utilityOfRenting(Household me, int q) {
        double leftForConsumption = 1.0 - Model.rentalMarket.getAverageSalePrice(q)/me.monthlyEmploymentIncome;
        return(utilityOfHome(me,q) + qualityOfLiving(leftForConsumption));
    }


    public int findBestRentalQuality(Household me) {
        int bestQ = 0;
        double bestU = utilityOfRenting(me,0);
        double u;
        for(int q = 0; q<House.Config.N_QUALITY; ++q) {
            u = utilityOfRenting(me, q);
            if(u > bestU) {
                bestU = u;
                bestQ = q;
            }
        }
        return(bestQ);
    }

    public double utilityOfPurchase(Household me, int q, MortgageAgreement mortgage) {
        double price = Model.housingMarket.getAverageSalePrice(q);
        if(price > mortgage.purchasePrice)    return(-10.0);
        double principal = price - mortgage.downPayment;
        double leftForConsumption = 1.0 - (principal*Model.bank.monthlyPaymentFactor(true) - price*HPAExpectation()/12.0)/me.monthlyEmploymentIncome;
        return(utilityOfHome(me,q) + qualityOfLiving(leftForConsumption));
        
    }
    
    public PurchasePlan findBestPurchase(Household me) {
        PurchasePlan result = new PurchasePlan();
        MortgageAgreement maxMortgage = Model.bank.requestApproval(me, Model.bank.getMaxMortgage(me, true), downPayment(me) + me.getHomeEquity(), true);

        result.quality = 0;
        result.utility = utilityOfPurchase(me,0,maxMortgage);
        double u;
        for(int q = 1; q<House.Config.N_QUALITY; ++q) {
            u = utilityOfPurchase(me, q, maxMortgage);
            if(u > result.utility) {
                result.utility = u;
                result.quality = q;
            }
        }
        return(result);
        
    }

    public double utilityOfHome(Household me, int q) {
        final double rmo = 0.3; // reference housing spend
        final double k = 0.5; // flexibility of spend on hpi change
        final double c = k*rmo/(1.0+rmo*(k-1.0));     // 0.0968
        final double lambda = (rmo-c)/(1-rmo);        // 0.290
        double Pref = (0.05/12.0)*Model.housingMarket.referencePrice(q)/me.monthlyEmploymentIncome;
        if(Pref < c) return(-10.0);
        return(lambda*Math.log(Pref-c));
    }
    
    public double qualityOfLiving(double consumptionFraction) {
        return(Math.log(consumptionFraction));
    }
    */
}
