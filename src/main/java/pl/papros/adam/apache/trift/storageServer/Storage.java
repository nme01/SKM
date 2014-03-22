package pl.papros.adam.apache.trift.storageServer;

import java.util.ArrayList;

public class Storage {

	private ArrayList<Data> data;

	public Storage() {
		data = new ArrayList<Data>();
	}

	public Storage(ArrayList<Data> data) {
		super();
		this.data = data;
	}

	public ArrayList<Data> getData() {
		return data;
	}

	public void setData(ArrayList<Data> data) {
		this.data = data;
	}

}
