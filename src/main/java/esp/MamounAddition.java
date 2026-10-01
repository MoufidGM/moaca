package esp;

public class MamounAddition
{

	public int m_intOne = 0;
	public int m_intTwo = 0;
	public MamounAddition( int intNumOne, int intNumDeux)
	{
		m_intOne = intNumOne;
		m_intTwo = intNumDeux;
	}

	public void executeAddition()
	{
		System.out.println("Addition Result is...:" + ( m_intOne + m_intTwo) );
	}
	public void executeSoustraction()
	{
		System.out.println("Soustraction Result is...:" + ( m_intOne - m_intTwo) );
	}
	public void executeMultiplication()
	{
		System.out.println("Multiplication  Result is...:" + ( m_intOne * m_intTwo) );
	}


	public static void main(String[] args)
	{
		MamounAddition mamounAddition = new MamounAddition(10		, 5);
		mamounAddition.executeAddition();
		mamounAddition.executeSoustraction();
		mamounAddition.executeMultiplication();
	}
}
