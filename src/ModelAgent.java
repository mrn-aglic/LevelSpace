import org.nlogo.api.Argument;
import org.nlogo.api.ExtensionException;
import org.nlogo.api.LogoException;
import org.nlogo.nvm.CommandTask;
import org.nlogo.nvm.ExtensionContext;


public class ModelAgent implements Agent {
	LevelsModelAbstract theModel;
	
	public ModelAgent(LevelsModelAbstract theModel){
		this.theModel = theModel;
	}
	

	public void ask(Argument args[], org.nlogo.api.Context context) throws ExtensionException, LogoException{

		Object rawCommand = args[1].get();
		int n = args.length - 2;
		Object[] actuals = new Object[n];
		for (int i = 0; i < n; i++) {
			actuals[i] = args[i+2].get();
		}
			CommandTask task;
			if (rawCommand instanceof CommandTask) {
				task = (CommandTask) rawCommand;
			} else {
				String command = rawCommand.toString();
				if (actuals.length > 0) {
					task = (CommandTask) theModel.report("task [ " + command + " ]");
				} else {
					// No arguments, don't bother making a task and such
					theModel.command(command);
					return;
				}
			}
			// this doesn't work because we're using the wrong context
			theModel.command(((ExtensionContext) context).nvmContext(), task, actuals);

	}
	@Override
	public Object of(String s) throws ExtensionException {
		Object reportedValue = theModel.report(s);
		// We need to deal with different return types
		// If the model reports an agent, we need to wrap it in the TurtleAgent class
		if (reportedValue instanceof org.nlogo.agent.Agent){
			org.nlogo.api.Agent theAgent = (org.nlogo.api.Agent)reportedValue;
			return new TurtleAgent(this, theAgent);
		}
		// If the model reports an agentset, we need to wrap it in the LevelSpaceAgentSet class
		else if (reportedValue instanceof org.nlogo.api.AgentSet){
			org.nlogo.api.AgentSet reportedAgentSet = (org.nlogo.api.AgentSet)reportedValue;
			LevelSpaceAgentSet returnAgentSet = new LevelSpaceAgentSet();
			for (org.nlogo.api.Agent agent : reportedAgentSet.agents()){
				TurtleAgent anAgent = new TurtleAgent(this, agent);
				returnAgentSet.add(anAgent);
			}
			return returnAgentSet;
		}
		else {
			return reportedValue;
		}
	}
}


