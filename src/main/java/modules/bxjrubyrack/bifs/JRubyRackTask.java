package modules.bxjrubyrack.bifs;

import modules.bxjrubyrack.RackRuntime;
import ortus.boxlang.runtime.bifs.*;
import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.scopes.*;
import ortus.boxlang.runtime.types.*;

/** Invokes an explicit Rake task in a fresh core-owned session. */
@BoxBIF
public final class JRubyRackTask extends BIF {
    /**
     * Declares configuration, task name and optional structured arguments for jrubyRackTask.
     */
    public JRubyRackTask() {
        declaredArguments = new Argument[] {
            new Argument(true, "struct", Key.of("config")),
            new Argument(true, "string", Key.of("task")),
            new Argument(false, "array", Key.of("args"), new Array())
        };
    }

    /**
     * Runs an explicit Rake task using validated BoxLang arguments.
     *
     * @param context the calling BoxLang context used for path resolution
     * @param args the validated configuration, task and structured arguments
     * @return the task result and captured output
     */
    @Override
    public Object _invoke(IBoxContext context, ArgumentsScope args) {
        return RackRuntime.task(
            context,
            args.getAsStruct(Key.of("config")),
            args.getAsString(Key.of("task")),
            args.getAsArray(Key.of("args"))
        );
    }
}
