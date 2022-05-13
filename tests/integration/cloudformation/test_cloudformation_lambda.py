import os

import jinja2

from localstack.testing.aws.cloudformation_utils import load_template_file
from localstack.utils.common import short_uid, to_str
from localstack.utils.generic.wait_utils import wait_until


# TODO: refactor file and remove this compatibility fn
def load_template_raw(file_name: str):
    return load_template_file(os.path.join(os.path.dirname(__file__), "../templates", file_name))


def test_lambda_autogenerated_name(
    cfn_client,
    lambda_client,
    cleanup_stacks,
    cleanup_changesets,
    is_change_set_created_and_available,
    is_stack_created,
):
    stack_name = f"stack-{short_uid()}"
    change_set_name = f"change-set-{short_uid()}"
    lambda_functional_id = f"MyFn{short_uid()}"
    template_rendered = jinja2.Template(load_template_raw("cfn_lambda_noname.yaml")).render(
        lambda_functional_id=lambda_functional_id
    )

    response = cfn_client.create_change_set(
        StackName=stack_name,
        ChangeSetName=change_set_name,
        TemplateBody=template_rendered,
        ChangeSetType="CREATE",
    )
    change_set_id = response["Id"]
    stack_id = response["StackId"]

    try:
        wait_until(is_change_set_created_and_available(change_set_id))
        cfn_client.execute_change_set(ChangeSetName=change_set_id)
        wait_until(is_stack_created(stack_id))

        outputs = cfn_client.describe_stacks(StackName=stack_id)["Stacks"][0]["Outputs"]
        assert len(outputs) == 1
        assert lambda_functional_id in outputs[0]["OutputValue"]

    finally:
        cleanup_changesets([change_set_id])
        cleanup_stacks([stack_id])


def test_update_lambda_inline_code(
    cfn_client, lambda_client, is_stack_created, is_stack_updated, cleanup_stacks
):
    stack_name = f"stack-{short_uid()}"
    function_name = f"test-fn-{short_uid()}"

    try:
        template_1 = jinja2.Template(load_template_raw("lambda_inline_code.yaml")).render(
            lambda_return_value="hello world",
            arch="x86_64",
            function_name=function_name,
        )
        response = cfn_client.create_stack(
            StackName=stack_name, TemplateBody=template_1, Capabilities=["CAPABILITY_IAM"]
        )
        stack_id = response["StackId"]
        assert stack_id
        wait_until(is_stack_created(stack_id))

        rs = lambda_client.get_function(FunctionName=function_name)
        assert function_name == rs["Configuration"]["FunctionName"]
        assert "x86_64" in rs["Configuration"]["Architectures"]
        result = lambda_client.invoke(FunctionName=function_name)
        result = to_str(result["Payload"].read())
        assert result.strip('" \n') == "hello world"

        template_2 = jinja2.Template(load_template_raw("lambda_inline_code.yaml")).render(
            lambda_return_value="hello globe", arch="arm64", function_name=function_name
        )
        cfn_client.update_stack(
            StackName=stack_name, TemplateBody=template_2, Capabilities=["CAPABILITY_IAM"]
        )
        wait_until(is_stack_updated(stack_id))

        rs = lambda_client.get_function(FunctionName=function_name)
        assert function_name == rs["Configuration"]["FunctionName"]
        assert "arm64" in rs["Configuration"]["Architectures"]
        result = lambda_client.invoke(FunctionName=function_name)
        result = to_str(result["Payload"].read())
        assert result.strip('" \n') == "hello globe"
    finally:
        # cleanup
        cleanup_stacks([stack_name])
