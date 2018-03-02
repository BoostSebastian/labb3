package Labb3;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;

import javax.sound.midi.SysexMessage;
import javax.swing.plaf.synth.SynthScrollBarUI;

public class TFTPServer 
{
	public static final int TFTPPORT = 4970;
	public static final int BUFSIZE = 516;
	public static final String READDIR = "C:\\Users\\Elias\\Desktop\\labb3\\read\\"; //custom address at your PC
	public static final String WRITEDIR = "C:\\Users\\Elias\\Desktop\\labb3\\write\\"; //custom address at your PC
	public static final String[] errorMessages = {"Not defined.", "File not found.", "Access violation.", "Disk full or allocation exceeded.",
													"Illegal TFTP operation.", "Unknown transfer ID.", "File already exists.", "No such user."};
	// OP codes
	public static final short OP_RRQ = 1;
	public static final short OP_WRQ = 2;
	public static final short OP_DAT = 3;
	public static final short OP_ACK = 4;
	public static final short OP_ERR = 5;

	public static void main(String[] args) {
		if (args.length > 0) 
		{
			System.err.printf("usage: java %s\n", TFTPServer.class.getCanonicalName());
			System.exit(1);
		}
		//Starting the server
		try 
		{
			TFTPServer server = new TFTPServer();
			server.start();
		}
		catch (SocketException e) 
			{e.printStackTrace();}
	}
	
	private void start() throws SocketException {
		byte[] buf = new byte[BUFSIZE];
		
		// Create socket
		DatagramSocket socket= new DatagramSocket(null);
		
		// Create local bind point 
		SocketAddress localBindPoint= new InetSocketAddress(TFTPPORT);
		socket.bind(localBindPoint);

		System.out.printf("Listening at port %d for new requests\n", TFTPPORT);

		while(true){   // Loop to handle client requests 
			final InetSocketAddress clientAddress = receiveFrom(socket, buf);
			// If clientAddress is null, an error occurred in receiveFrom()
			if (clientAddress == null) 
				continue;

			final StringBuffer requestedFile = new StringBuffer();
			final int reqtype = ParseRQ(buf, requestedFile);
			System.out.println(reqtype);
			System.out.println(requestedFile);
			new Thread() {
				public void run() {
					try {
						DatagramSocket sendSocket = new DatagramSocket(0);
						sendSocket.connect(clientAddress);	// Connect to client					
						
						System.out.printf("%s request for %s from %s using port %d\n",
								(reqtype == OP_RRQ)?"Read":"Write", requestedFile.toString(),
								clientAddress.getHostName(), clientAddress.getPort());
								
						// Read request
						if (reqtype == OP_RRQ) {      
							requestedFile.insert(0, READDIR);
							HandleRQ(sendSocket, requestedFile.toString(), OP_RRQ);
						}
						// Write request
						else {                       
							requestedFile.insert(0, WRITEDIR);
							HandleRQ(sendSocket,requestedFile.toString(), OP_WRQ);  
						}
						sendSocket.close();
					} 
					catch (SocketException e) 
						{e.printStackTrace();}
				}
			}.start();
		}
	}
	
	/**
	 * Reads the first block of data, i.e., the request for an action (read or write).
	 * @param socket (socket to read from)
	 * @param buf (where to store the read data)
	 * @return socketAddress (the socket address of the client)
	 */
	private InetSocketAddress receiveFrom(DatagramSocket socket, byte[] buf) 
	{
		
		// Create datagram packet
		DatagramPacket receivePacket = new DatagramPacket(buf, buf.length);
		// Receive packet
		try {
			socket.receive(receivePacket);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			System.err.println("Could not receive the datagrampacket.");
			e.printStackTrace();
		}
		// Get client address and port from the packet
		InetSocketAddress socketAddress = new InetSocketAddress(
				receivePacket.getAddress(), receivePacket.getPort());
		return socketAddress;
	}

	/**
	 * Parses the request in buf to retrieve the type of request and requestedFile
	 * 
	 * @param buf (received request)
	 * @param requestedFile (name of file to read/write)
	 * @return opcode (request type: RRQ or WRQ)
	 */
	private int ParseRQ(byte[] buf, StringBuffer requestedFile) 
	{
		// See "TFTP Formats" in TFTP specification for the RRQ/WRQ request contents
		ByteBuffer bBuf = ByteBuffer.wrap(buf);
		requestedFile.append(new String(buf, 2, buf.length - 2).split("\0")[0]);
		return bBuf.getShort();
	}

	/**
	 * Handles RRQ and WRQ requests 
	 * 
	 * @param sendSocket (socket used to send/receive packets)
	 * @param requestedFile (name of file to read/write)
	 * @param opcode (RRQ or WRQ)
	 */
	private void HandleRQ(DatagramSocket sendSocket, String requestedFile, int opcode) {
		File file = new File(requestedFile);
		byte[] buf = new byte[BUFSIZE-4];
		System.out.println("Handling request " + opcode);
		System.out.println("Requested file: " + requestedFile);
		if(opcode == OP_RRQ)
		{
			FileInputStream input = null;
			try {
				input = new FileInputStream(file);
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch blocks
				System.err.println("File Not Found");
				send_ERR(sendSocket, (short)1, errorMessages[1]);
				e.printStackTrace();
				return;
			}
			
			short blockValue = 1;
			
			while (true){
				ByteBuffer buffer = ByteBuffer.allocate(BUFSIZE);
				int length;
				try{
					length = input.read(buf);
					if(length == -1)
						length = 0;
					else if(length < 512)
						buffer = ByteBuffer.allocate(length);
				} catch (IOException e){
					System.err.println("Error while trying to reading file.");
					break;
				}
				
				

				buffer.putShort(OP_DAT);
				buffer.putShort(blockValue);
				buffer.put(buf, 0, length);
				DatagramPacket sendData = new DatagramPacket(buffer.array(), length + 4);
				
				System.out.println("Blocknumber: " + blockValue);
				if((send_DATA_receive_ACK(sendSocket, sendData, blockValue++)))
				{
					if(length < 512)
					{
						try {
							input.close();
						} catch (IOException e) {
							System.err.println("Unable to close inputstream.");
							send_ERR(sendSocket, (short)0, "Unable to close inputstream.");
							e.printStackTrace();
						}
						break;
					}
				}
				else
				{
					System.err.println("Not Defined");
					send_ERR(sendSocket, (short)0, errorMessages[0]);
					break;
				}
			}
		}
		else if (opcode == OP_WRQ) 
		{
			if(file.exists() && !file.isDirectory())
			{
				System.err.println("File already exists.");
				send_ERR(sendSocket, (short)6, errorMessages[6]);
			}
			else
			{
				try {
					file.createNewFile();
				} catch (IOException e1) {
					System.err.println("Unable to create file.");
					send_ERR(sendSocket, (short)2, errorMessages[2]);
					e1.printStackTrace();
				}
				FileOutputStream output = null;
				try {
					output = new FileOutputStream(file);
				} catch (FileNotFoundException e) {
					System.err.println("Problem creating FileOutputStream.");
					send_ERR(sendSocket, (short)2, errorMessages[2]);
					e.printStackTrace();
				}
				
				short blockValue = 0;
				while(true)
				{
 
					ByteBuffer buffer = ByteBuffer.allocate(BUFSIZE);
					buffer.putShort(OP_ACK);
					buffer.putShort(blockValue++);
					DatagramPacket ackPacket = new DatagramPacket(buffer.array(), 4);
					DatagramPacket receivedData = receive_DATA_send_ACK(sendSocket, ackPacket, blockValue);
					if(receivedData == null)
					{
						System.err.println("DatagramPacket receivedData may be corrupt.");
						send_ERR(sendSocket, (short)0, errorMessages[0]);
					}
						
					buf = receivedData.getData();
					int length = buf.length;
					try {
						output.write(buf);
						output.flush();
					} catch (IOException e) {
						System.err.println("Unable to write to " + file);
						send_ERR(sendSocket, (short)2, errorMessages[2]);
						e.printStackTrace();
					}
					if(length < 512)
					{
						try {
							output.close();
						} catch (IOException e) {
							System.err.println("Unable to close outputstream...");
							send_ERR(sendSocket, (short)0, "Unable to close outputstream.");
							e.printStackTrace();
						}
						break;
					}
				}
			}
		}
		else 
		{
			send_ERR(sendSocket, (short)4, "Illegal TFTP operation.");
			sendSocket.close();
			System.err.println("Invalid request. Sending an error packet.");
			return;
		}		
	}	

	private boolean send_DATA_receive_ACK(DatagramSocket sendSocket, DatagramPacket packetSend, short blockValue){
		byte[] buffer = new byte[BUFSIZE];
		DatagramPacket received = new DatagramPacket(buffer, buffer.length);
		int RETRY_COUNTER = 0;
		while(true)
		{
			
			if (RETRY_COUNTER >=5) {
	            System.err.println("Timed out. Closing connection.");
	            return false;
	        }
			RETRY_COUNTER++;
			
			try {
				sendSocket.send(packetSend);
				System.out.println("Sent.");
				sendSocket.setSoTimeout(((int) Math.pow(2, RETRY_COUNTER))*1000);
				sendSocket.receive(received);

				short ack_Block = checkOpcodeGetBlock(received);
				System.out.println("ack_BlockNumber: " + ack_Block);
				if(ack_Block == blockValue){
					return true;
				}
				else{
					return false;
				}
			} catch (IOException e){
				System.err.println("Didn't receive any acknownlegdement.");
				send_ERR(sendSocket, (short)0, "Didn't receive any acknownlegdement");
				e.printStackTrace();
			}
			
		}
	}
	
	private DatagramPacket receive_DATA_send_ACK(DatagramSocket sendSocket, DatagramPacket sendAck, short blockValue){
		byte[] buffer = new byte[BUFSIZE];
		DatagramPacket received = new DatagramPacket(buffer, buffer.length);
		while(true)
		{
			try {
				sendSocket.send(sendAck);
				System.out.println("Sent acknownlegdement.");
				sendSocket.receive(received);
				System.out.println("Packet received with ");
				short ack_Block = checkOpcodeGetBlock(received);
				System.out.print("block" + ack_Block + "\n");
				if(ack_Block == blockValue){
					return received;
				}
				else{
					return null;
				}
			} catch (IOException e) {
				System.err.println("No packet");
				send_ERR(sendSocket, (short)0, "No packet");
				e.printStackTrace();
			}
		}
	}
//	  2 bytes     2 bytes      string    1 byte
//	  -----------------------------------------
//	 | Opcode |  ErrorCode |   ErrMsg   |   0  |
//	  -----------------------------------------	
	private void send_ERR(DatagramSocket sendSocket, short errorCode, String errMsg){
		ByteBuffer buffer = ByteBuffer.allocate(BUFSIZE);
		buffer.putShort(OP_ERR);
		buffer.putShort(errorCode);
		buffer.put(errMsg.getBytes());
		buffer.put((byte)0);
		
		DatagramPacket receivedPacket = new DatagramPacket(buffer.array(), buffer.array().length);
		try {
			sendSocket.send(receivedPacket);
		} catch (IOException e) {
			System.err.println("Problem sending error packet.");
			e.printStackTrace();
		}
	}
	
//    2 bytes     2 bytes      string    1 byte
//    -----------------------------------------
//   | Opcode |  ErrorCode |   ErrMsg   |   0  |
//    -----------------------------------------
	private void received_ERR(ByteBuffer buf, short opCode)
	{	
		ByteBuffer buffer = buf;
		short OP_ERRCODE = buffer.getShort();
		String params = buffer.toString();
		System.err.println("We've received an error from the client!");
		System.err.println(opCode + " | " + OP_ERRCODE + " | " + params);
		
	}
//    2 bytes     2 bytes
//    ---------------------
//   | Opcode |   Block #  |
//    ---------------------
	private short checkOpcodeGetBlock(DatagramPacket ack){
		ByteBuffer buffer = ByteBuffer.wrap(ack.getData());
		short opCode = buffer.getShort();
		System.out.println("CheckOpcodeGetBlock");
		System.out.println("First two bytes (Opcode): " + opCode);
		if(opCode == OP_ERR)
		{
			received_ERR(buffer, opCode);
			return -1;
		}
		// returns the block
		short blockValue = buffer.getShort();
		System.out.println("Second two bytes (BlockNumber): " + blockValue);
		return blockValue;
	}
}



