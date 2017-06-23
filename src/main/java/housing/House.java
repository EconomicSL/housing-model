package housing;

import java.io.Serializable;

/************************************************
 * Class representing a house.
 * Use this to represent the intrinsic properties of the house.
 * 
 * @author daniel
 *
 ************************************************/
public class House implements Comparable<House>, Serializable {
	private static final long serialVersionUID = 4538336934216907799L;

//	static public class Config {
//		public static int N_QUALITY = 48; // number of quality bands
//	}
	
	public House() {
		id = ++id_pool;	
		resident = null;
		owner = null;
		quality = (int)(rand.nextDouble()*config.N_QUALITY);
	}
	
	public boolean isOnMarket() {
		return saleRecord != null;
	}
	
	public HouseSaleRecord getSaleRecord() {
		return saleRecord;
	}

	public HouseSaleRecord getRentalRecord() {
		return rentalRecord;
	}
	
	public boolean isOnRentalMarket() {
		return rentalRecord != null;
	}

	public void putForSale(HouseSaleRecord saleRecord) {
		this.saleRecord = saleRecord;
	}
	public void resetSaleRecord() {
		saleRecord = null;
	}

	public void putForRent(HouseSaleRecord rentalRecord) {
		this.rentalRecord = rentalRecord;
	}
	public void resetRentalRecord() {
		rentalRecord = null;
	}

	public int getQuality() {
		return quality;
	}

	private Model.MersenneTwister	rand = Model.rand;	// Passes the Model's random number generator to a private field
	private int				quality;
	public IHouseOwner  	owner;
	public Household		resident;
	public int				id;
	public HouseSaleRecord	saleRecord;
	public HouseSaleRecord	rentalRecord;
	
	static int 				id_pool = 0;
	
	@Override
	public int compareTo(House o) {
		return((int)Math.signum(id-o.id));
	}
	
}
