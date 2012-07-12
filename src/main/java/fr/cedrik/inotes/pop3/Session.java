/**
 *
 */
package fr.cedrik.inotes.pop3;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.StringTokenizer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fr.cedrik.inotes.pop3.commands.PASS;

/**
 * @author C&eacute;drik LIME
 */
public class Session {
	private static final String CR_LF = "\r\n";//$NON-NLS-1$
	private static final String END_OF_COMMAND_RESULT = ".";//$NON-NLS-1$

	protected final Logger logger = LoggerFactory.getLogger(this.getClass());
	protected final Socket clientSocket;
	protected final BufferedReader in;
	protected final Writer out;
	protected final Context context = new Context();
	protected final Map<String, POP3Command> commands = new HashMap<String, POP3Command>();

	{
		ServiceLoader<POP3Command> services = ServiceLoader.load(POP3Command.class);
		for (POP3Command command : services) {
			logger.trace("Discovered new POP3 command: {}", command.getClass().getSimpleName());
			commands.put(command.getClass().getSimpleName(), command);
		}
	}

	public Session(Socket clientSocket) throws IOException {
		this.clientSocket = clientSocket;
		in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), DEFAULT_ENCODING));
		out = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream(), DEFAULT_ENCODING));
	}
	private static final Charset DEFAULT_ENCODING = Charset.forName("ISO-8859-15");

	protected String filterMultiLineResponseLine(String line) {
		if (line.startsWith(END_OF_COMMAND_RESULT)) {
			return END_OF_COMMAND_RESULT + line;
		} else {
			return line;
		}
	}

	public void run() throws IOException {
		out.append(ResponseStatus.POSITIVE.toString("POP3 server ready")).append(CR_LF).flush();
		context.inputArgs = "";
		while (context.inputArgs != null) {
			String inputLine = in.readLine();
			StringTokenizer tokenizer = new StringTokenizer(inputLine.trim());
			if (tokenizer.countTokens() == 0) {
				continue;
			}
			String requestedCommand = tokenizer.nextToken();
			POP3Command pop3Command = commands.get(requestedCommand.toUpperCase());
			if (pop3Command == null) {
				out.append(ResponseStatus.NEGATIVE.toString(requestedCommand)).append(CR_LF).flush();
				continue;
			}
			if (! pop3Command.isValid(context)) {
				out.append(ResponseStatus.NEGATIVE.toString("invalid state")).append(CR_LF).flush();
				continue;
			}
			if (pop3Command instanceof PASS) {
				// don't echo password in logs!
				logger.debug(requestedCommand);
			} else {
				logger.debug(inputLine);
			}
			context.inputArgs = inputLine.substring(requestedCommand.length()).trim();
			Iterator<String> responseLines = pop3Command.call(context);
			int nLines = 0;
			while (responseLines.hasNext()) {
				String line = responseLines.next();
				assert ! line.contains("\r") : line;
				assert ! line.contains("\n") : line;
				if (nLines == 0) {
					// log only response status
					logger.debug(line);
				} else {
					logger.trace(line);
				}
				out.append(filterMultiLineResponseLine(line)).append(CR_LF);
				++nLines;
			}
			if (nLines > 1) {
				logger.trace(END_OF_COMMAND_RESULT);
				out.append(END_OF_COMMAND_RESULT).append(CR_LF);
			}
			out.flush();
		}
		in.close();
		out.close();
		clientSocket.close();
	}
}