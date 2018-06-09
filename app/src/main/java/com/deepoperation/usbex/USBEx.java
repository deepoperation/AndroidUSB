/**
 * Copyright (C) 2013 Ratoc Systems, Inc.
 *
 * Copyright (C) 2018 deepoperation
 */

package com.deepoperation.usbex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.ftdi.j2xx.D2xxManager;
import com.ftdi.j2xx.FT_Device;

/**
 * Created by Takashi on 2017/12/07.
 */

public class USBEx extends Activity {
	// for Debug
	private static final String TAG = "Usb60BCR.10161530";
	private static boolean D = true;

	static Context DeviceUARTContext;

	private UsbManager mUsbManager = null;

	public static D2xxManager ftdid2xx = null;
	FT_Device ftDev = null;
	D2xxManager.DriverParameters d2xxDrvParameter;

	int iVid = 0x0584;	// Vendor ID of RATOC
	int iPid = 0xb020;	// Defualt:REX-USB60F
	int iPidTb[] = {
			0xb020,	// REX-USB60F
			0xb02f,	// REX-USB60MI
			0xb03B,	// REX-USB60MB
			0xffff};
	int iDevid = 0;	// DeviceId

	static int iEnableReadFlag = 1;

	// local variables
	int baudRate;	// baud rate
	byte dataBit;	// 8:8bit, 7:7bit
	byte parity;	// 0:none, 1:odd, 2:even, 3:mark, 4:space
	byte stopBit;	// 1:1 stop bits, 2:2 stop bits
	byte flowControl;	// 0:none, 1:flow control (CTS/RTS)
	int portNumber;
	ArrayList<CharSequence> portNumberList;

	public static final int readLength = 512;
	public int readcount = 0;
	public int iavailable = 0;
	public int savePosition = 0;
	public int saveLength = 0;
	byte[] readData;
	char[] readDataToText;
	public boolean bReadThreadGoing = false;	// Thread flag
	public readThread read_thread;

	boolean uart_configured = false;

	AlertDialog alertDlg = null;

//	private ArrayAdapter<String> adapter;

	// Status, Header, Raw Data View
	private TextView mTvStatus;		// Status view
	private TextView mTvDigits;		// Digits
	private TextView mTvBarcode;	// Barcode

	private String mBCRtext;

	// BCR Header & Terminate Code for KEYENCE BL-N60
	private static char StxCode = 0x02, EscCode = 0x1B,
			ExtCode = 0x03, EotCode = 0x04,
			LFCode = 0x0a, CRCode = 0x0d;

	Handler mHandler = new Handler();

	private static final String ACTION_USB_PERMISSION =
			"com.android.example.USB_PERMISSION";
	private Intent mIntent;
	private PendingIntent mPermissionIntent;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		InitScreen();

		readData = new byte[readLength];
		readDataToText = new char[readLength+2];

		baudRate = 9600;	// baudrate = 9600baud
		dataBit = 8;		// data bit = 8bit
		parity = 0;			// parity = none
		stopBit = 1;		// stop bit - 1bit
		flowControl = 1;	// flowcontrol = RST/CTS
		portNumber = 1;

		mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
		if (mUsbManager == null)
			if (D) Log.e(TAG, "+++ Cnanot getSystemService for USBMnager +++");


		try {
			ftdid2xx = D2xxManager.getInstance(DeviceUARTContext = this);
		} catch (D2xxManager.D2xxException ex) {
			ex.printStackTrace();
		}

		if (ftdid2xx != null) {
			int i;
			for(i = 0; iPidTb[i] != 0xffff; i++) {
				if(!ftdid2xx.setVIDPID(iVid,iPidTb[i]))		// Set VID/PID of target USB device
					Log.i("ftd2xx-java","setVIDPID Error");
			}
		}

		mIntent = new Intent(ACTION_USB_PERMISSION);
		mPermissionIntent = PendingIntent.getBroadcast(this, 0, mIntent, 0);

		IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

		registerReceiver(mUsbReceiver, filter);		// register for USB60 unpluged

	}

	private void InitScreen() {
		// Set up the window layout
		setContentView(R.layout.main);

		mTvStatus = (TextView)findViewById(R.id.tvStatus);
		mTvStatus.setTextSize(20);
		mTvDigits = (TextView)findViewById(R.id.tvDigits);
		mTvDigits.setTextSize(20);
		mTvBarcode = (TextView)findViewById(R.id.tvBarcode);
		mTvBarcode.setTextSize(20);

		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
	}

	private UsbDevice Search_MyUsbSerial() {
		UsbDevice mUsbDevice = null;
		int openIndex = 0;	// searched device at first

		int tempDevCount = ftdid2xx.createDeviceInfoList(DeviceUARTContext);
		Log.i("Misc Function Test ",
				"Device number = " + Integer.toString(tempDevCount));

		if (tempDevCount == 0) {
			return null;
		}

		D2xxManager.FtDeviceInfoListNode DevInfoNode = ftdid2xx.getDeviceInfoListDetail(openIndex);
		int MyDeviceId = DevInfoNode.location / 16;
		HashMap<String, UsbDevice> deviceList = mUsbManager.getDeviceList();
		Iterator<UsbDevice> deviceIterator  = deviceList.values().iterator();
		// Search UsbDevice object
		while(deviceIterator.hasNext()){
			UsbDevice mUsbDev = deviceIterator.next();
			if (MyDeviceId == mUsbDev.getDeviceId()) {
				mUsbDevice = mUsbDev;	// get UsbDevice object
				break;
			}
		}

		if (D) {
			iVid = mUsbDevice.getVendorId();
			iPid = mUsbDevice.getProductId();
			iDevid = mUsbDevice.getDeviceId();
			Log.i(TAG, "VenderId: " + Integer.toHexString(iVid) +
					"  ProductId: " + Integer.toHexString(iPid) +
					"  Device Id: " + Integer.toHexString(iDevid));
		}

		return mUsbDevice;
	}


	private boolean Start_MyUsbSerial(UsbDevice MyUsbDevice) {

		if (connectFunction(MyUsbDevice) == false) {
			mTvStatus.setText(R.string.Notconnected);
			return false;
		}

		SetConfig(baudRate, dataBit, stopBit, parity, flowControl);
		mTvStatus.setText(R.string.Connected);

		if (D) Log.e(TAG, "+++ Start_MyUsbSerial OK !!! +++");
		return true;
	}

	public void End_MyUsbSerial() {

		bReadThreadGoing = false;
		try {
			Thread.sleep(50);
		}
		catch (InterruptedException e) {
			e.printStackTrace();
		}

		if(ftDev != null) {
			synchronized(ftDev) {
				if( true == ftDev.isOpen()) {
					ftDev.close();
				}
			}
			ftDev = null;
		}
	}

	// When resume & hotlug for plug-in
	@Override
	public void onResume() {
		super.onResume();

		if (D) Log.e(TAG, "+++ onResume +++");

		if (ftDev != null)
			return;

		UsbDevice mUsbDevice = Search_MyUsbSerial();

		if(mUsbDevice != null) {

			mUsbManager.requestPermission(mUsbDevice, mPermissionIntent);

			return;
		}

		MyUsbSerialErrDlg();
	}

	@Override
	public void onStop() {
		if (D) Log.e(TAG, "+++ onStop +++");
		End_MyUsbSerial();
		super.onStop();
	}

	/**
	 * Alert Dialog for USB-Serial error */

	private void MyUsbSerialErrDlg()
	{
		if (alertDlg != null)
			return;

		alertDlg = new AlertDialog.Builder(this)
				.setTitle(R.string.app_name)
				.setMessage(R.string.alert_NotConnect)
				.setPositiveButton(R.string.btn_ok,
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog, int which) {
							}
						}
				)
				.show();
	}

	public boolean connectFunction(UsbDevice MyUsbDevice)
	{

		if (MyUsbDevice == null)
			return false;

		if(null == ftDev) {
			ftDev = ftdid2xx.openByUsbDevice(DeviceUARTContext, MyUsbDevice);
		} else {
			synchronized(ftDev)
			{
				ftDev = ftdid2xx.openByUsbDevice(DeviceUARTContext, MyUsbDevice);
			}
		}

		if(ftDev == null) {
			Toast.makeText(DeviceUARTContext,"open device port NG, ftDev == null", Toast.LENGTH_LONG).show();
			return false;
		}

		if (true == ftDev.isOpen()) {
			Toast.makeText(DeviceUARTContext, "open device port OK", Toast.LENGTH_SHORT).show();

			if(false == bReadThreadGoing) {
				read_thread = new readThread(handler);
				read_thread.start();
				bReadThreadGoing = true;
			}
		} else {
			Toast.makeText(DeviceUARTContext, "open device port NG", Toast.LENGTH_LONG).show();
			return false;
		}

		return true;

	}

	public void SetConfig(int baud, byte dataBits, byte stopBits, byte parity, byte flowControl)
	{
		if (ftDev.isOpen() == false) {
			Log.e("j2xx", "SetConfig: device not open");
			return;
		}

		// configure our port
		// reset to UART mode for 232 devices
		ftDev.setBitMode((byte) 0, D2xxManager.FT_BITMODE_RESET);

		ftDev.setBaudRate(baud);

		switch (dataBits) {
			case 7:
				dataBits = D2xxManager.FT_DATA_BITS_7;
				break;
			case 8:
				dataBits = D2xxManager.FT_DATA_BITS_8;
				break;
			default:
				dataBits = D2xxManager.FT_DATA_BITS_8;
				break;
		}

		switch (stopBits) {
			case 1:
				stopBits = D2xxManager.FT_STOP_BITS_1;
				break;
			case 2:
				stopBits = D2xxManager.FT_STOP_BITS_2;
				break;
			default:
				stopBits = D2xxManager.FT_STOP_BITS_1;
				break;
		}

		switch (parity) {
			case 0:
				parity = D2xxManager.FT_PARITY_NONE;
				break;
			case 1:
				parity = D2xxManager.FT_PARITY_ODD;
				break;
			case 2:
				parity = D2xxManager.FT_PARITY_EVEN;
				break;
			case 3:
				parity = D2xxManager.FT_PARITY_MARK;
				break;
			case 4:
				parity = D2xxManager.FT_PARITY_SPACE;
				break;
			default:
				parity = D2xxManager.FT_PARITY_NONE;
				break;
		}

		ftDev.setDataCharacteristics(dataBits, stopBits, parity);

		short flowCtrlSetting;
		switch (flowControl) {
			case 0:
				flowCtrlSetting = D2xxManager.FT_FLOW_NONE;
				break;
			case 1:
				flowCtrlSetting = D2xxManager.FT_FLOW_RTS_CTS;
				break;
			case 2:
				flowCtrlSetting = D2xxManager.FT_FLOW_DTR_DSR;
				break;
			case 3:
				flowCtrlSetting = D2xxManager.FT_FLOW_XON_XOFF;
				break;
			default:
				flowCtrlSetting = D2xxManager.FT_FLOW_NONE;
				break;
		}

		// flow ctrl: XOFF/XOM
		ftDev.setFlowControl(flowCtrlSetting, (byte) 0x0b, (byte) 0x0d);

		uart_configured = true;
		Toast.makeText(DeviceUARTContext, "Config done", Toast.LENGTH_SHORT).show();

	}

	private void ShowSerailInfo() {

		String sSerialInfo, sParity, sFlowControl;

		switch (parity) {
			case 0:
				sParity = "NONE";
				break;
			case 1:
				sParity = "ODD";
				break;
			case 2:
				sParity = "EVEN";
				break;
			case 3:
				sParity = "MARK";
				break;
			case 4:
				sParity = "SPACE";
				break;
			default:
				sParity = "NONE";
				break;
		}

		switch (flowControl) {
			case 0:
				sFlowControl = "NONE";
				break;
			case 1:
				sFlowControl = "RTS/CTS";
				break;
			case 2:
				sFlowControl = "DTR/DSR";
				break;
			case 3:
				sFlowControl = "XON/XOFF";
				break;
			default:
				sFlowControl = "NONE";
				break;
		}

		sSerialInfo = getString(R.string.h_BaudRate) + " : " + String.valueOf(baudRate) + "\n"
				+ getString(R.string.h_DataBit) + " : " + String.valueOf(dataBit) + "\n"
				+ getString(R.string.h_ParityBit) + " : " + sParity + "\n"
				+ getString(R.string.h_StopBit) + " : " + String.valueOf(stopBit) + "\n"
				+ getString(R.string.h_FlowControl) + " : " + sFlowControl;

		alertDlg = new AlertDialog.Builder(this)
				.setTitle(getString(R.string.app_name) + " - Serial Setting")
				.setMessage(sSerialInfo)
				.setPositiveButton(R.string.btn_ok,
						new DialogInterface.OnClickListener() {
							public void onClick(DialogInterface dialog, int which) {
							}
						}
				)
				.show();
	}

	private void ShowBarcodeInfo() {

		String sDigits =  "Digis : " + String.valueOf(mBCRtext.length());
		String sBarcode = "Barcode : " + mBCRtext;

		mTvDigits.setText(sDigits);
		mTvBarcode.setText(sBarcode);

	}

	final Handler handler =  new Handler()
	{
		@Override
		public void handleMessage(Message msg) {

			int rChar;
			int rbufp;

			int Startp, Endp, len;

			mBCRtext = "";
			Startp = Endp = 0;

			if (alertDlg != null)
				alertDlg.dismiss();
			if(saveLength > 0) {
				for (rbufp = 0; rbufp < saveLength; rbufp++) {

					rChar = (byte)(readDataToText[rbufp] & 0x007F);

					if (rChar == StxCode || rChar == EscCode) {
						Startp = rbufp;
						mBCRtext = "";
						continue;
					}

					// Detect terminate code
					if (rChar == ExtCode
							|| rChar == EotCode
							|| rChar == LFCode
							|| rChar == CRCode) {
						Endp = rbufp;

						if (Endp == 0)
							continue;
						len = Endp - Startp;

						mBCRtext = String.valueOf(readDataToText, Startp, len);

						ShowBarcodeInfo();

					}

				}
				saveLength = 0;
			}
		}
	};

	private class readThread  extends Thread {
		Handler mHandler;

		readThread(Handler h){
			mHandler = h;
			this.setPriority(Thread.MIN_PRIORITY);
		}

		@Override
		public void run()
		{
			int i;

			while(true == bReadThreadGoing) {
				try {
					Thread.sleep(50);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

				synchronized(ftDev)
				{
					iavailable = ftDev.getQueueStatus();
					if (iavailable > 0) {

						if(iavailable > readLength){
							iavailable = readLength;
						}
						if((savePosition + iavailable) > readLength)
							iavailable = readLength - savePosition;

						ftDev.read(readData, iavailable);
						for (i = 0; i < iavailable; i++) {
							readDataToText[savePosition+i] = (char) readData[i];
						}
						savePosition += i;
						readDataToText[savePosition] = '\0';

						if (savePosition == readLength) {	// overflow
							saveLength = savePosition;
							Message msg = mHandler.obtainMessage();
							mHandler.sendMessage(msg);
							savePosition = 0;
						}

					} else {
						if (savePosition > 0) {
							saveLength = savePosition;
							Message msg = mHandler.obtainMessage();
							mHandler.sendMessage(msg);
							savePosition = 0;
						}
					}
				}
			}
		}

	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.main, menu);

		return true;
	}

	@Override
	public boolean onMenuItemSelected(int featureId, MenuItem item) {
		super.onMenuItemSelected(featureId, item);
		switch(item.getItemId()) {
			case R.id.menu_reconnect:
				End_MyUsbSerial();
				Start_MyUsbSerial(Search_MyUsbSerial());
				return false;

			case R.id.menu_serialInfo:
				ShowSerailInfo();	// Display - baud, databit, prity, stopbit, flowcontrol
				return false;

			default:
				return true;
		}
	}
	//
	// Broardcast Receiver for USB60 Remove
	//
	BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();

			Log.d(TAG, "onReceive action : " + action);

			if (ACTION_USB_PERMISSION.equals(action)) {
				synchronized (this) {
					UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

					if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
						if (device != null) {
							if (alertDlg !=null) {
								alertDlg.dismiss();
								alertDlg = null;
							}
//    						Log.d(TAG, "Permission granted " + device);
							Start_MyUsbSerial(device);
						}
					} else {
						Log.d(TAG, "permission denied for device " + device);
					}
				}
			} else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
				End_MyUsbSerial();
				mTvStatus.setText(R.string.Notconnected);
			}
		}
	};
}
